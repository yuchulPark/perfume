package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevBrandDiscoveryServiceTests.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.perfume.domain.Perfume;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;
import com.perfume.scentrev.service.ScentRevBrandImportException.Stage;

class ScentRevBrandPhase1ImportServiceTests {

    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevPhase1ImportService importer = mock(ScentRevPhase1ImportService.class);
    private final ScentRevBrandDiscoveryService discovery = new ScentRevBrandDiscoveryService(client);
    private final ScentRevBrandPhase1ImportService service = new ScentRevBrandPhase1ImportService(discovery, client, importer);

    @Test
    void completesDiscoveryBeforeProfilesAndImportsSequentiallyOncePerUniqueSlug() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a", "creed-b"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, "creed-b", "creed-c"));
        var a = profile("creed", "creed-a");
        var b = profile("creed", "creed-b");
        var c = profile("creed", "creed-c");
        when(client.getFragranceProfile("creed-a")).thenReturn(a);
        when(client.getFragranceProfile("creed-b")).thenReturn(b);
        when(client.getFragranceProfile("creed-c")).thenReturn(c);
        var callingThread = Thread.currentThread();
        when(importer.importProfile(any())).thenAnswer(call -> {
            assertThat(Thread.currentThread()).isSameAs(callingThread);
            return mock(Perfume.class);
        });

        var result = service.importBrand("creed");

        assertThat(result.discovery().brandSlug()).isEqualTo("creed");
        assertThat(result.discovery().discoveredResultCount()).isEqualTo(4);
        assertThat(result.discovery().uniqueFragranceCount()).isEqualTo(3);
        assertThat(result.discovery().duplicateDiscoveryCount()).isEqualTo(1);
        assertThat(result.successfullyProcessedCount()).isEqualTo(3);
        var order = inOrder(client, importer);
        order.verify(client).searchFragrancesFiltered("creed", 0);
        order.verify(client).searchFragrancesFiltered("creed", 10);
        order.verify(client).getFragranceProfile("creed-a");
        order.verify(importer).importProfile(a);
        order.verify(client).getFragranceProfile("creed-b");
        order.verify(importer).importProfile(b);
        order.verify(client).getFragranceProfile("creed-c");
        order.verify(importer).importProfile(c);
        verifyNoMoreInteractions(client, importer);
    }

    @Test
    void emptyCatalogMakesNoProfileOrPersistenceCalls() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false));
        assertThat(service.importBrand("creed").successfullyProcessedCount()).isZero();
        verify(client).searchFragrancesFiltered("creed", 0);
        verifyNoMoreInteractions(client);
        verifyNoInteractions(importer);
    }

    @Test
    void profileBrandMismatchFailsBeforeAnyPersistence() {
        discoverOne();
        when(client.getFragranceProfile("creed-a")).thenReturn(profile("other", "creed-a"));
        assertImportFailure(Stage.IDENTITY, 0, "creed-a", "brand/fragrance identity");
        verifyNoInteractions(importer);
    }

    @Test
    void returnedSlugMismatchFailsBeforeAnyPersistence() {
        discoverOne();
        when(client.getFragranceProfile("creed-a")).thenReturn(profile("creed", "creed-other"));
        assertImportFailure(Stage.IDENTITY, 0, "creed-a", "brand/fragrance identity");
        verifyNoInteractions(importer);
    }

    @Test
    void publicIdMismatchFailsBeforeAnyPersistence() {
        discoverOne();
        var identity = new ScentRevIdentity("A", "different-id", "Creed", "creed", "creed-a", null, null, null, null, null);
        when(client.getFragranceProfile("creed-a")).thenReturn(new ScentRevFragranceProfileResponse(
                identity, null, null, null, null, null, null, null));
        assertImportFailure(Stage.IDENTITY, 0, "creed-a", "public ID");
        verifyNoInteractions(importer);
    }

    @Test
    void nullProfileOrMissingIdentityFailsBeforePersistence() {
        discoverOne();
        for (var profile : Arrays.asList(null, new ScentRevFragranceProfileResponse(null, null, null, null, null, null, null, null))) {
            when(client.getFragranceProfile("creed-a")).thenReturn(profile);
            assertImportFailure(Stage.IDENTITY, 0, "creed-a", "identity");
        }
        verifyNoInteractions(importer);
    }

    @Test
    void profileFailureReportsCompletedCountAndDoesNotRequestLaterProfilesOrRetry() {
        discoverThree();
        var first = profile("creed", "creed-a");
        when(client.getFragranceProfile("creed-a")).thenReturn(first);
        when(client.getFragranceProfile("creed-b")).thenThrow(new IllegalStateException("fake-secret"));
        assertImportFailure(Stage.PROFILE, 1, "creed-b", "MCP profile request failed");
        var order = inOrder(client, importer);
        order.verify(client).searchFragrancesFiltered("creed", 0);
        order.verify(client).getFragranceProfile("creed-a");
        order.verify(importer).importProfile(first);
        order.verify(client).getFragranceProfile("creed-b");
        verifyNoMoreInteractions(client, importer);
    }

    @Test
    void mapperFailureReportsCompletedCountAndDoesNotRetryOrRequestLaterProfile() {
        discoverThree();
        var first = profile("creed", "creed-a");
        var second = profile("creed", "creed-b");
        when(client.getFragranceProfile("creed-a")).thenReturn(first);
        when(client.getFragranceProfile("creed-b")).thenReturn(second);
        when(importer.importProfile(second)).thenThrow(new IllegalArgumentException("fake-secret"));
        assertImportFailure(Stage.PERSISTENCE, 1, "creed-b", "earlier successful imports remain committed");
        var order = inOrder(client, importer);
        order.verify(client).searchFragrancesFiltered("creed", 0);
        order.verify(client).getFragranceProfile("creed-a");
        order.verify(importer).importProfile(first);
        order.verify(client).getFragranceProfile("creed-b");
        order.verify(importer).importProfile(second);
        verifyNoMoreInteractions(client, importer);
    }

    @Test
    void laterIdentityMismatchKeepsEarlierSuccessfulProgress() {
        discoverThree();
        when(client.getFragranceProfile("creed-a")).thenReturn(profile("creed", "creed-a"));
        when(client.getFragranceProfile("creed-b")).thenReturn(profile("other", "creed-b"));
        assertImportFailure(Stage.IDENTITY, 1, "creed-b", "identity");
        verify(importer).importProfile(profile("creed", "creed-a"));
        verifyNoMoreInteractions(importer);
        verify(client, never()).getFragranceProfile("creed-c");
    }

    @Test
    void discoveryFailurePreventsAllProfileAndPersistenceCalls() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a"));
        when(client.searchFragrancesFiltered("creed", 10)).thenThrow(new IllegalStateException("fake-secret"));
        assertThatThrownBy(() -> service.importBrand("creed")).isInstanceOf(ScentRevBrandImportException.class)
                .hasMessageContaining("discovering")
                .hasMessageNotContaining("fake-secret");
        verify(client).searchFragrancesFiltered("creed", 0);
        verify(client).searchFragrancesFiltered("creed", 10);
        verifyNoMoreInteractions(client);
        verifyNoInteractions(importer);
    }

    @Test
    void explicitRerunAfterPartialFailureDelegatesEarlierProfilesAgainToIdempotentMapper() {
        discoverThree();
        var first = profile("creed", "creed-a");
        var second = profile("creed", "creed-b");
        var third = profile("creed", "creed-c");
        when(client.getFragranceProfile("creed-a")).thenReturn(first);
        when(client.getFragranceProfile("creed-b")).thenThrow(new IllegalStateException("fake-secret")).thenReturn(second);
        when(client.getFragranceProfile("creed-c")).thenReturn(third);
        var existing = mock(Perfume.class);
        when(importer.importProfile(first)).thenReturn(existing);
        assertImportFailure(Stage.PROFILE, 1, "creed-b", "profile request");

        assertThat(service.importBrand("creed").successfullyProcessedCount()).isEqualTo(3);

        verify(importer, times(2)).importProfile(first);
        verify(importer).importProfile(second);
        verify(importer).importProfile(third);
        verifyNoMoreInteractions(importer);
        verify(client, times(2)).searchFragrancesFiltered("creed", 0);
        verify(client, times(2)).getFragranceProfile("creed-a");
        verify(client, times(2)).getFragranceProfile("creed-b");
        verify(client).getFragranceProfile("creed-c");
        verifyNoMoreInteractions(client);
    }

    private void discoverOne() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, "creed-a"));
    }

    private void discoverThree() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, "creed-a", "creed-b", "creed-c"));
    }

    private void assertImportFailure(Stage stage, int completed, String slug, String diagnostic) {
        assertThatThrownBy(() -> service.importBrand("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("brand creed fragrance " + (completed + 1))
                .hasMessageContaining(slug).hasMessageContaining(diagnostic).hasMessageNotContaining("fake-secret")
                .hasNoCause().satisfies(error -> {
                    var failure = (ScentRevBrandImportException) error;
                    assertThat(failure.getStage()).isEqualTo(stage);
                    assertThat(failure.getSuccessfullyProcessedCount()).isEqualTo(completed);
                    assertThat(failure.getFailedFragranceSlug()).isEqualTo(slug);
                    assertThat(failure.getFailedOffset()).isNull();
                    assertThat(failure.getDiscovery().brandSlug()).isEqualTo("creed");
                    assertThat(failure.getDiscovery().uniqueFragranceCount()).isPositive();
                    assertThat(failure.getSuppressed()).isEmpty();
                });
    }

    static ScentRevFragranceProfileResponse profile(String brand, String slug) {
        return new ScentRevFragranceProfileResponse(new ScentRevIdentity(slug, "id-" + slug, "Creed", brand,
                slug, null, null, null, null, null), null, List.of(), null, null, null, null, null);
    }
}
