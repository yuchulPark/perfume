package com.perfume.ai.transport;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class RecommendationCancellationTests {
    @Test void aCapturedButDetachedCallbackCannotInterruptTheNextTaskOnAReusedWorker() throws Exception {
        var scope = new RecommendationCancellation(); var cancelling = new CountDownLatch(1); var release = new CountDownLatch(1);
        var attached = new CountDownLatch(1); var detached = new CountDownLatch(1); var checkNext = new CountDownLatch(1);
        var interruptedNext = new AtomicBoolean();
        scope.onCancel(() -> { cancelling.countDown(); try { release.await(); } catch (InterruptedException stop) { throw new AssertionError(stop); } });
        var worker = new Thread(() -> {
            try {
                try (var binding = scope.bind()) { attached.countDown(); cancelling.await(); }
                Thread.interrupted(); detached.countDown(); checkNext.await(); interruptedNext.set(Thread.currentThread().isInterrupted());
            } catch (Exception failure) { interruptedNext.set(true); }
        });
        var canceller = new Thread(scope::cancel);
        try {
            worker.start(); assertThat(attached.await(2, TimeUnit.SECONDS)).isTrue(); canceller.start();
            assertThat(detached.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown(); canceller.join(2000); checkNext.countDown(); worker.join(2000);
            assertThat(interruptedNext).isFalse(); assertThat(worker.isAlive()).isFalse();
        } finally { release.countDown(); checkNext.countDown(); worker.interrupt(); canceller.interrupt(); }
    }
    @Test void cancelledBeforeAttachmentStillInterruptsOnlyThatScopeAndChildCompletionIsIndependentOfFutureDone() throws Exception {
        var scope = new RecommendationCancellation(); var child = scope.child(); var exited = new CountDownLatch(1);
        var waiter = new Thread(() -> { scope.awaitChildren(); exited.countDown(); }); waiter.start();
        assertThat(exited.await(30, TimeUnit.MILLISECONDS)).isFalse(); child.close(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue(); waiter.join();
        scope.cancel();
        try (var binding = scope.bind()) { assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
        assertThat(RecommendationCancellation.current()).isNull();
    }
}
