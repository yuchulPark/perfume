package com.perfume.ai.embedding;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A bounded invocation. Failed inspected ranges stay queued while later/new ranges get a turn. */
public final class AutoPerfumeEmbeddingJob {
    private static final Logger log = LoggerFactory.getLogger(AutoPerfumeEmbeddingJob.class);
    private final PerfumeEmbeddingStore store;
    private final PerfumeEmbeddingBatch batch;
    private final LongSupplier nanoTime;

    public AutoPerfumeEmbeddingJob(PerfumeEmbeddingStore store, PerfumeEmbeddingBatch batch) {
        this(store, batch, System::nanoTime);
    }

    AutoPerfumeEmbeddingJob(PerfumeEmbeddingStore store, PerfumeEmbeddingBatch batch, LongSupplier nanoTime) {
        this.store = store; this.batch = batch; this.nanoTime = nanoTime;
    }

    public AutoEmbeddingState.Report run(EmbeddingBatchOptions options, AutoEmbeddingOptions automatic, BooleanSupplier stopping) {
        var started = Instant.now();
        long startNanos = nanoTime.getAsLong();
        // State IO must remain possible when entering with an already interrupted worker.
        boolean initiallyInterrupted = Thread.interrupted();
        var stop = new StopBudget(stopping, startNanos, automatic.maxRuntime().toNanos(), initiallyInterrupted);
        log.info("Automatic embedding started: startedAt={}", started);
        try (var stateFile = new AutoEmbeddingState(automatic.stateDirectory())) {
            var state = stateFile.read(options.model());
            var work = new ArrayDeque<>(state.pending());
            var deferred = new ArrayList<AutoEmbeddingState.Range>();
            long total = -1, observed = state.observedUpperId();
            var counts = new Counts();
            String fatalCode = null;
            try {
                if (!stop.getAsBoolean()) {
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
                        // Admit once, before any upsert. Never open another full scan in this invocation.
                        checkpoint(stateFile, options, observed, pending(work, deferred), state.lastSuccessAt(),
                                report(started, startNanos, "RUNNING", "IN_PROGRESS", total, counts, observed, pending(work, deferred), false));
                        long available = 0;
                        for (var range : work) {
                            if (stop.getAsBoolean()) { break; }
                            available += session.window(range.afterId(), range.upperId()).available();
                        }
                        if (!stop.getAsBoolean()) {
                            counts.available = available; counts.target = Math.min(available, options.maxItems());
                        }
                    }
                }
                while (!work.isEmpty() && counts.processed < options.maxItems() && !stop.getAsBoolean()) {
                    var selected = work.getFirst();
                    // Bound each checkpoint interval by the existing page size; all intervals share one budget.
                    int limit = (int) Math.min(options.pageSize(), options.maxItems() - counts.processed);
                    var runOptions = new EmbeddingBatchOptions(options.model(), options.baseUrl(), options.pageSize(),
                            limit, selected.afterId(), options.delayMs(), options.connectTimeout(), options.readTimeout());
                    PerfumeEmbeddingBatch.Result result;
                    try { result = batch.execute(runOptions, selected.upperId(), stop); }
                    catch (EmbeddingFailure failure) { result = failure.result(); fatalCode = failure.code().name(); }
                    if (result != null) {
                        if (result.processed() < 0 || result.processed() > limit || result.lastId() < selected.afterId()
                                || result.lastId() > selected.upperId() || (result.processed() > 0 && result.lastId() == selected.afterId())) {
                            result = null; fatalCode = "INVALID_PROGRESS";
                        } else {
                            counts.add(result);
                            if (result.interrupted()) { stop.interrupted(); }
                            if (result.hasMore() && result.processed() == 0 && fatalCode == null && !stop.getAsBoolean()) {
                                fatalCode = "NO_PROGRESS";
                            }
                        }
                    } else if (fatalCode == null) { fatalCode = "INVALID_PROGRESS"; }
                    boolean interrupted = stop.getAsBoolean();
                    work.removeFirst();
                    boolean replay = fatalCode != null || interrupted || (result != null && result.failed() > 0);
                    if (result != null && result.lastId() > selected.afterId()) {
                        if (result.lastId() < selected.upperId() && (replay || result.hasMore())) {
                            work.addLast(new AutoEmbeddingState.Range(result.lastId(), selected.upperId()));
                        }
                        if (replay) { deferred.add(new AutoEmbeddingState.Range(selected.afterId(), result.lastId())); }
                    } else if (replay) { deferred.add(selected); }
                    var pending = pending(work, deferred);
                    checkpoint(stateFile, options, observed, pending, state.lastSuccessAt(),
                            report(started, startNanos, "RUNNING", "IN_PROGRESS", total, counts, observed, pending, interrupted));
                    if (fatalCode != null || interrupted) { break; }
                }
            } catch (EmbeddingFailure failure) {
                fatalCode = failure.code().name();
            }
            boolean interrupted = stop.getAsBoolean();
            String status = fatalCode != null ? ("BATCH_BUSY".equals(fatalCode) && counts.processed == 0 ? "SKIPPED" : "FAILED")
                    : interrupted ? "INTERRUPTED" : counts.failed > 0 ? "FAILED" : "SUCCESS";
            String code = fatalCode != null ? fatalCode : interrupted ? stop.code : counts.failed > 0 ? "PARTIAL_FAILURE" : "OK";
            var pending = pending(work, deferred);
            var report = report(started, startNanos, status, code, total, counts, observed, pending, interrupted);
            checkpoint(stateFile, options, observed, pending, "SUCCESS".equals(status) ? report.finishedAt() : state.lastSuccessAt(), report);
            var result = report.result();
            log.info("Automatic embedding finished: startedAt={}, finishedAt={}, status={}, code={}, total={}, available={}, target={}, processed={}, new={}, changed={}, skipped={}, failed={}, lastId={}, upperId={}, hasMore={}, interrupted={}, nextCursor={}, pendingRanges={}, elapsedMs={}",
                    report.startedAt(), report.finishedAt(), status, code, total,
                    result.available(), result.target(), result.processed(), result.created(), result.changed(), result.skipped(), result.failed(),
                    result.lastId(), result.upperId(), result.hasMore(), result.interrupted(), report.nextCursor(), pending.size(), report.elapsedMs());
            return report;
        } catch (java.io.IOException | RuntimeException failure) {
            // Never attach IO/JDBC exceptions: their messages can contain paths, SQL or credentials.
            log.error("Automatic embedding stopped: code=STATE_OR_EXECUTION_ERROR");
            throw new IllegalStateException("Automatic embedding stopped: STATE_OR_EXECUTION_ERROR");
        } finally {
            if (initiallyInterrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static List<AutoEmbeddingState.Range> pending(Deque<AutoEmbeddingState.Range> work, List<AutoEmbeddingState.Range> deferred) {
        var pending = new ArrayList<>(work); pending.addAll(deferred); return pending;
    }

    private static void checkpoint(AutoEmbeddingState file, EmbeddingBatchOptions options, long upper,
            List<AutoEmbeddingState.Range> pending, String success, AutoEmbeddingState.Report report) throws java.io.IOException {
        boolean interrupted = Thread.interrupted();
        try { file.write(new AutoEmbeddingState.Snapshot(1, options.model(), upper, pending, success, report)); }
        finally { if (interrupted) { Thread.currentThread().interrupt(); } }
    }

    private AutoEmbeddingState.Report report(Instant started, long startNanos, String status, String code, long total,
            Counts counts, long upper, List<AutoEmbeddingState.Range> pending, boolean interrupted) {
        return new AutoEmbeddingState.Report(started.toString(), Instant.now().toString(), status, code,
                (nanoTime.getAsLong() - startNanos) / 1_000_000, total,
                new PerfumeEmbeddingBatch.Result(counts.available, counts.target, counts.processed, counts.created, counts.changed,
                        counts.skipped, counts.failed, counts.lastId, upper, !pending.isEmpty(), interrupted),
                pending.isEmpty() ? 0 : pending.get(0).afterId(), pending.size());
    }

    private static final class Counts {
        long available = -1, target = -1, processed, created, changed, skipped, failed, lastId;
        void add(PerfumeEmbeddingBatch.Result result) {
            processed += result.processed(); created += result.created(); changed += result.changed();
            skipped += result.skipped(); failed += result.failed();
            if (result.processed() > 0) { lastId = result.lastId(); }
        }
    }

    private final class StopBudget implements BooleanSupplier {
        private final BooleanSupplier requested;
        private final long started, limit;
        private String code;
        StopBudget(BooleanSupplier requested, long started, long limit, boolean interrupted) {
            this.requested = requested; this.started = started; this.limit = limit;
            if (interrupted) { code = "STOPPED"; }
        }
        @Override public boolean getAsBoolean() {
            if (code == null) {
                if (Thread.currentThread().isInterrupted() || requested.getAsBoolean()) { code = "STOPPED"; }
                else if (nanoTime.getAsLong() - started >= limit) { code = "TIME_LIMIT"; }
            }
            return code != null;
        }
        void interrupted() { if (!getAsBoolean()) { code = "STOPPED"; } }
    }
}
