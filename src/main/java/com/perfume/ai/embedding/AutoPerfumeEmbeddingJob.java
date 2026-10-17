package com.perfume.ai.embedding;

import java.time.Instant;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bounded invocation. Failed inspected ranges stay queued while later/new ranges get a turn. */
public final class AutoPerfumeEmbeddingJob {
    private static final Logger log = LoggerFactory.getLogger(AutoPerfumeEmbeddingJob.class);
    private final PerfumeEmbeddingStore store;
    private final PerfumeEmbeddingBatch batch;

    public AutoPerfumeEmbeddingJob(PerfumeEmbeddingStore store, PerfumeEmbeddingBatch batch) {
        this.store = store; this.batch = batch;
    }

    public AutoEmbeddingState.Report run(EmbeddingBatchOptions options, AutoEmbeddingOptions automatic, BooleanSupplier stopping) {
        var started = Instant.now();
        long startNanos = System.nanoTime();
        BooleanSupplier stop = () -> stopping.getAsBoolean()
                || System.nanoTime() - startNanos >= automatic.maxRuntime().toNanos();
        log.info("Automatic embedding started: startedAt={}", started);
        try (var stateFile = new AutoEmbeddingState(automatic.stateDirectory())) {
            var state = stateFile.read(options.model());
            var work = new ArrayList<>(state.pending());
            long total = -1, observed = state.observedUpperId();
            PerfumeEmbeddingBatch.Result result = null;
            String code = "OK", status = "SUCCESS";
            AutoEmbeddingState.Range selected = null;
            boolean executing = false;
            try {
                try (var session = store.open()) {
                    var catalog = session.window(0);
                    total = catalog.available();
                    if (work.isEmpty()) {
                        observed = catalog.upperId();
                        if (observed > 0) { work.add(new AutoEmbeddingState.Range(0, observed)); }
                    } else if (catalog.upperId() > observed) {
                        work.add(new AutoEmbeddingState.Range(observed, catalog.upperId()));
                        observed = catalog.upperId();
                    }
                }
                // Persist admission before doing work. A crash replays the original, unadvanced range.
                stateFile.write(new AutoEmbeddingState.Snapshot(1, options.model(), observed, work, state.lastSuccessAt(), state.latest()));
                if (!work.isEmpty()) {
                    selected = work.get(0);
                    var runOptions = new EmbeddingBatchOptions(options.model(), options.baseUrl(), options.pageSize(),
                            options.maxItems(), selected.afterId(), options.delayMs(), options.connectTimeout(), options.readTimeout());
                    executing = true;
                    result = batch.execute(runOptions, selected.upperId(), stop);
                    if (result.failed() > 0) { code = "PARTIAL_FAILURE"; status = "FAILED"; }
                    else if (result.interrupted()) { code = stopping.getAsBoolean() ? "STOPPED" : "TIME_LIMIT"; status = "INTERRUPTED"; }
                }
            } catch (EmbeddingFailure failure) {
                result = failure.result(); code = failure.code().name();
                status = failure.code() == EmbeddingFailure.Code.BATCH_BUSY ? "SKIPPED" : "FAILED";
            }
            if (executing && selected != null) {
                work.remove(0);
                if ("SUCCESS".equals(status)) {
                    if (result.hasMore()) { work.add(new AutoEmbeddingState.Range(result.lastId(), selected.upperId())); }
                } else if (result != null && result.lastId() > selected.afterId()) {
                    // Never acknowledge a failed prefix: retain it for full hash-based replay.
                    if (result.lastId() < selected.upperId()) {
                        work.add(new AutoEmbeddingState.Range(result.lastId(), selected.upperId()));
                    }
                    work.add(new AutoEmbeddingState.Range(selected.afterId(), result.lastId()));
                } else { work.add(selected); }
            }
            var report = new AutoEmbeddingState.Report(started.toString(), Instant.now().toString(), status, code,
                    (System.nanoTime() - startNanos) / 1_000_000, total, result,
                    work.isEmpty() ? 0 : work.get(0).afterId(), work.size());
            stateFile.write(new AutoEmbeddingState.Snapshot(1, options.model(), observed, work,
                    "SUCCESS".equals(status) ? report.finishedAt() : state.lastSuccessAt(), report));
            log.info("Automatic embedding finished: startedAt={}, finishedAt={}, status={}, code={}, total={}, available={}, target={}, processed={}, new={}, changed={}, skipped={}, failed={}, lastId={}, upperId={}, hasMore={}, interrupted={}, nextCursor={}, pendingRanges={}, elapsedMs={}",
                    report.startedAt(), report.finishedAt(), status, code, total,
                    result == null ? -1 : result.available(), result == null ? -1 : result.target(),
                    result == null ? 0 : result.processed(), result == null ? 0 : result.created(),
                    result == null ? 0 : result.changed(), result == null ? 0 : result.skipped(), result == null ? 0 : result.failed(),
                    result == null ? 0 : result.lastId(), result == null ? -1 : result.upperId(),
                    result != null && result.hasMore(), result != null && result.interrupted(), report.nextCursor(), work.size(), report.elapsedMs());
            return report;
        } catch (java.io.IOException | RuntimeException failure) {
            // Never attach IO/JDBC exceptions: their messages can contain paths, SQL or credentials.
            log.error("Automatic embedding stopped: code=STATE_OR_EXECUTION_ERROR");
            throw new IllegalStateException("Automatic embedding stopped: STATE_OR_EXECUTION_ERROR");
        }
    }
}
