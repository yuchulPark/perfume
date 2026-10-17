package com.perfume.ai.embedding;

import java.util.Arrays;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Explicit administrator CLI only. Never exposed as an HTTP endpoint or scheduler. */
public class PerfumeEmbeddingBatchRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PerfumeEmbeddingBatchRunner.class);
    private final PerfumeEmbeddingBatch batch;
    private final EmbeddingBatchOptions options;

    public PerfumeEmbeddingBatchRunner(PerfumeEmbeddingBatch batch, EmbeddingBatchOptions options) {
        this.batch = batch; this.options = options;
    }

    public static void configureManualExecution(SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                prepare(event.getSpringApplication(), event.getEnvironment(), event.getArgs()));
    }

    static void prepare(SpringApplication application, ConfigurableEnvironment environment, String[] args) {
        if (environment.getProperty(AutoEmbeddingOptions.ENABLED, Boolean.class, false)
                && !environment.getProperty(EmbeddingBatchOptions.ENABLED, Boolean.class, false)) {
            throw new IllegalArgumentException("Automatic embedding requires the explicit batch execution mode.");
        }
        if (!environment.getProperty(EmbeddingBatchOptions.ENABLED, Boolean.class, false)) { return; }
        if (!Arrays.asList(args).contains("--" + EmbeddingBatchOptions.ENABLED + "=true")) {
            throw new IllegalArgumentException("Embedding batches require the explicit --" + EmbeddingBatchOptions.ENABLED + "=true CLI flag.");
        }
        if (environment.getProperty("scentrev.brand-import", Boolean.class, false)) {
            throw new IllegalArgumentException("Run the embedding batch separately from ScentRev Import.");
        }
        EmbeddingBatchOptions.from(environment); // Validate all limits and the pinned model before JDBC startup.
        if (environment.getProperty(AutoEmbeddingOptions.ENABLED, Boolean.class, false)) {
            if (!Arrays.asList(args).contains("--" + AutoEmbeddingOptions.ENABLED + "=true")) {
                throw new IllegalArgumentException("Automatic batches require an explicit CLI flag.");
            }
            AutoEmbeddingOptions.from(environment);
            if (environment.getProperty(EmbeddingBatchOptions.PREFIX + "batch.after-id", Long.class, 0L) != 0L) {
                throw new IllegalArgumentException("Automatic batches use their persisted range cursors.");
            }
            application.setRegisterShutdownHook(false); // Automatic runner waits before closing the datasource.
        }
        application.setWebApplicationType(WebApplicationType.NONE);
        environment.getPropertySources().addFirst(new MapPropertySource("perfumeEmbeddingManualExecution", Map.of(
                "spring.main.web-application-type", "none", "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.sql.init.mode", "never", "spring.jpa.show-sql", "false", "scentrev.brand-import", "false")));
    }

    @Override
    public void run(ApplicationArguments arguments) {
        PerfumeEmbeddingBatch.Result result;
        try { result = batch.execute(options); }
        catch (EmbeddingFailure failure) {
            if (failure.result() != null) { printResult(failure.result()); }
            log.error("Perfume embedding batch stopped: code={}", failure.code());
            throw failure;
        }
        printResult(result);
        if (result.failed() > 0 || result.interrupted()) { throw new EmbeddingFailure(EmbeddingFailure.Code.PARTIAL_FAILURE); }
    }

    private static void printResult(PerfumeEmbeddingBatch.Result result) {
        log.info("Perfume embedding batch: available={}, target={}, processed={}, new={}, changed={}, skipped={}, failed={}, lastId={}, upperId={}, hasMore={}, interrupted={}",
                result.available() < 0 ? "unavailable" : result.available(), result.target() < 0 ? "unavailable" : result.target(),
                result.processed(), result.created(), result.changed(), result.skipped(), result.failed(),
                result.lastId(), result.upperId() < 0 ? "unavailable" : result.upperId(), result.hasMore(), result.interrupted());
    }
}
