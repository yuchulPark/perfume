package com.perfume.ai.embedding;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

public final class AutoPerfumeEmbeddingRunner implements ApplicationRunner {
    private final AutoPerfumeEmbeddingJob job;
    private final EmbeddingBatchOptions options;
    private final AutoEmbeddingOptions automatic;

    public AutoPerfumeEmbeddingRunner(AutoPerfumeEmbeddingJob job, EmbeddingBatchOptions options, AutoEmbeddingOptions automatic) {
        this.job = job; this.options = options; this.automatic = automatic;
    }

    @Override public void run(ApplicationArguments arguments) {
        var stopping = new AtomicBoolean();
        var finished = new CountDownLatch(1);
        var hook = new Thread(() -> {
            stopping.set(true);
            try { finished.await(390, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }, "automatic-embedding-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            var report = job.run(options, automatic, stopping::get);
            if (!report.status().equals("SUCCESS") && !report.status().equals("SKIPPED")) {
                throw new IllegalStateException("Automatic embedding failed: " + report.code());
            }
        } finally {
            finished.countDown();
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (IllegalStateException shuttingDown) { /* Signal handler owns the wait. */ }
        }
    }
}
