package com.perfume.ai.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PerfumeEmbeddingBatch {
    private static final Logger log = LoggerFactory.getLogger(PerfumeEmbeddingBatch.class);
    private final PerfumeEmbeddingStore store;
    private final PerfumeEmbeddingClient client;
    private final PerfumeEmbeddingText texts;

    public PerfumeEmbeddingBatch(PerfumeEmbeddingStore store, PerfumeEmbeddingClient client, PerfumeEmbeddingText texts) {
        this.store = store; this.client = client; this.texts = texts;
    }

    public record Result(long available, long target, long processed, long created, long changed,
            long skipped, long failed, long lastId, long upperId, boolean hasMore, boolean interrupted) { }

    public Result execute(EmbeddingBatchOptions options) {
        return execute(options, null, () -> false);
    }

    /** Reuses the manual pipeline; automatic callers supply a fixed range and cooperative stop. */
    public Result execute(EmbeddingBatchOptions options, Long rangeUpperId, java.util.function.BooleanSupplier stop) {
        long processed = 0, created = 0, changed = 0, skipped = 0, failed = 0, lastId = options.afterId();
        long available = -1, target = -1, upperId = -1;
        boolean interrupted = false;
        boolean exhausted = false;
        try (var session = store.open()) {
            var window = rangeUpperId == null ? session.window(lastId) : session.window(lastId, rangeUpperId);
            available = window.available();
            upperId = window.upperId();
            target = Math.min(available, options.maxItems());
            if (target > 0 && !Thread.currentThread().isInterrupted() && !stop.getAsBoolean()) { client.verifyModel(options.model()); }
            while (processed < target) {
                if (Thread.currentThread().isInterrupted() || stop.getAsBoolean()) { interrupted = true; break; }
                var ids = session.nextIds(lastId, window.upperId(), (int) Math.min(options.pageSize(), target - processed));
                if (ids.isEmpty()) { exhausted = true; break; }
                for (long id : ids) {
                    if (Thread.currentThread().isInterrupted() || stop.getAsBoolean()) { interrupted = true; break; }
                    boolean attemptedEmbedding = false;
                    try {
                        var text = texts.render(session.load(id));
                        var hash = texts.hash(text);
                        var previous = session.findHash(id, options.model());
                        if (previous.filter(hash::equals).isPresent()) { skipped++; }
                        else {
                            attemptedEmbedding = true;
                            var vector = client.embed(options.model(), text);
                            EmbeddingVector.validate(vector);
                            if (!hash.equals(texts.hash(texts.render(session.load(id))))) {
                                throw new EmbeddingFailure(EmbeddingFailure.Code.SOURCE_CHANGED);
                            }
                            if (!session.save(id, options.model(), text, hash, vector)) { skipped++; }
                            else if (previous.isEmpty()) { created++; }
                            else { changed++; }
                        }
                    } catch (RuntimeException failure) {
                        failed++;
                        var code = failure instanceof EmbeddingFailure known ? known.code() : EmbeddingFailure.Code.INVALID_SOURCE;
                        log.warn("Perfume embedding failed: perfumeId={}, code={}", id, code);
                        if (code == EmbeddingFailure.Code.DATABASE) {
                            processed++;
                            lastId = id;
                            throw failure; // Leave the session immediately; close releases the advisory lock.
                        }
                    }
                    processed++;
                    lastId = id;
                    if (attemptedEmbedding && options.delayMs() > 0) {
                        try { Thread.sleep(options.delayMs()); }
                        catch (InterruptedException interruptedSleep) { Thread.currentThread().interrupt(); interrupted = true; break; }
                    }
                }
                if (interrupted) { break; }
            }
            return new Result(window.available(), target, processed, created, changed, skipped, failed, lastId,
                    window.upperId(), lastId < window.upperId() && (rangeUpperId == null || (!exhausted && processed < available)), interrupted);
        } catch (RuntimeException failure) {
            var code = failure instanceof EmbeddingFailure known ? known.code() : EmbeddingFailure.Code.DATABASE;
            throw new EmbeddingFailure(code, new Result(available, target, processed, created, changed, skipped,
                    failed, lastId, upperId, upperId >= 0 && lastId < upperId, interrupted));
        }
    }
}
