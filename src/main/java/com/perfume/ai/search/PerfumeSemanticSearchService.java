package com.perfume.ai.search;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.perfume.ai.transport.RecommendationCancellation;

/** No queue or unbounded async work. A cancelled HTTP request keeps its worker slot until the work really exits. */
public class PerfumeSemanticSearchService implements AutoCloseable {
    private final OllamaQueryEmbeddingAdapter embeddings;
    private final JdbcPerfumeSemanticSearchRepository repository;
    private final PerfumeSemanticSearchOptions options;
    private final ThreadPoolExecutor executor;
    public PerfumeSemanticSearchService(OllamaQueryEmbeddingAdapter embeddings, JdbcPerfumeSemanticSearchRepository repository,
            PerfumeSemanticSearchOptions options) {
        this.embeddings = embeddings; this.repository = repository; this.options = options;
        var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(options.maxConcurrent(), options.maxConcurrent(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
                    var thread = new Thread(task, "perfume-semantic-search-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public PerfumeSemanticSearchResponse search(PerfumeSemanticSearchRequest request) {
        if (Thread.currentThread().isInterrupted()) { throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_INTERRUPTED); }
        java.util.concurrent.Future<PerfumeSemanticSearchResponse> work;
        var scope = RecommendationCancellation.current();
        var child = scope == null ? null : scope.child();
        try {
            var task = new java.util.concurrent.FutureTask<PerfumeSemanticSearchResponse>(() -> {
                try (var binding = scope == null ? null : scope.bind()) {
                if (Thread.currentThread().isInterrupted() || (scope != null && scope.isCancelled())) throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_INTERRUPTED);
                var vector = embeddings.embed(request.query());
                if (Thread.currentThread().isInterrupted() || (scope != null && scope.isCancelled())) { throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_INTERRUPTED); }
                return PerfumeSemanticSearchResponse.of(repository.search(vector, options.model(), request.topK()));
                }
            }) {
                @Override public void run() {
                    try { super.run(); } finally { closeChild(child); }
                }
            };
            work = task; executor.execute(task);
        } catch (RejectedExecutionException busy) { closeChild(child); throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_BUSY); }
        try { return work.get(options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException timeout) { work.cancel(true); throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_TIMEOUT); }
        catch (InterruptedException stop) { work.cancel(true); Thread.currentThread().interrupt(); throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_INTERRUPTED); }
        catch (ExecutionException failed) {
            if (failed.getCause() instanceof SemanticSearchFailure known) { throw known; }
            throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_FAILED);
        }
    }
    private static void closeChild(AutoCloseable child) {
        if (child != null) { try { child.close(); } catch (Exception ignored) { } }
    }
    @Override public void close() { executor.shutdownNow(); }
}
