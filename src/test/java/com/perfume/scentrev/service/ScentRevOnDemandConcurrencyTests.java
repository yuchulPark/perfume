package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.OnDemandTestStore.profile;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.perfume.scentrev.client.ScentRevMcpClient;

class ScentRevOnDemandConcurrencyTests {
    @Test
    void overlappingSameSlugRequestsUseOneProfileAndOneImportThenReleaseTheLockEntry() throws Exception {
        var store = new OnDemandTestStore();
        var client = mock(ScentRevMcpClient.class);
        var service = new ScentRevOnDemandPerfumeService(client, store.cache);
        var enteredProfile = new CountDownLatch(1);
        var releaseProfile = new CountDownLatch(1);
        var secondThread = new AtomicReference<Thread>();
        var profileCalls = new AtomicInteger();
        when(client.getFragranceProfile("creed-selected")).thenAnswer(call -> {
            profileCalls.incrementAndGet();
            enteredProfile.countDown();
            if (!releaseProfile.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Unit test coordination timed out."); }
            return profile("creed-selected", "id-selected");
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.getOrImportBySlug("creed-selected"));
            assertThat(enteredProfile.await(3, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                return service.getOrImportBySlug("creed-selected");
            });
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(secondThread.get()).isNotNull();
                assertThat(secondThread.get().getState()).isEqualTo(Thread.State.WAITING);
            });
            // The mocked client method is synchronized. Observe the counter here instead of entering it for verification.
            assertThat(profileCalls.get()).isEqualTo(1); // Second caller is already blocked on the same slug.
            releaseProfile.countDown();
            var firstResult = first.get(3, TimeUnit.SECONDS);
            assertThat(second.get(3, TimeUnit.SECONDS)).isSameAs(firstResult);
            verify(client, times(1)).getFragranceProfile("creed-selected");
            verifyNoMoreInteractions(client);
            verify(store.mapper, times(1)).importProfile(any());
            assertThat(store.perfumeRows).hasSize(1);
            assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "slugLocks")).isEmpty();
        } finally {
            releaseProfile.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedSelectionReleasesItsLockAndASeparateExplicitRetryCanSucceed() {
        var store = new OnDemandTestStore();
        var client = mock(ScentRevMcpClient.class);
        var service = new ScentRevOnDemandPerfumeService(client, store.cache);
        when(client.getFragranceProfile("creed-selected")).thenThrow(new IllegalStateException("fake-private-secret"))
                .thenReturn(profile("creed-selected", "id-selected"));
        assertThatThrownBy(() -> service.getOrImportBySlug("creed-selected"))
                .isInstanceOf(ScentRevOnDemandException.class).hasNoCause().hasMessageNotContaining("fake-private-secret");
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "slugLocks")).isEmpty();
        assertThat(service.getOrImportBySlug("creed-selected").getFragranceSlug()).isEqualTo("creed-selected");
        verify(client, times(2)).getFragranceProfile("creed-selected"); // Two explicit service calls, one request each.
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(service, "slugLocks")).isEmpty();
    }
}
