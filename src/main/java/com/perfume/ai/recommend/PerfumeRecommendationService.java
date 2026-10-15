package com.perfume.ai.recommend;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import com.perfume.ai.search.PerfumeSemanticSearchRequest;
import com.perfume.ai.search.PerfumeSemanticSearchService;
import com.perfume.ai.search.SemanticSearchFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Separate workers call the existing search executor; neither executor waits for work submitted to itself. */
public class PerfumeRecommendationService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PerfumeRecommendationService.class);
    private final PerfumeSemanticSearchService search;
    private final JdbcRecommendationEvidenceRepository evidence;
    private final RecommendationPromptBuilder prompts;
    private final OllamaRecommendationClient llm;
    private final PerfumeRecommendationOptions options;
    private final ThreadPoolExecutor executor;
    private static class Progress { volatile String stage = "search"; volatile long generationDeadline = Long.MAX_VALUE; }
    public PerfumeRecommendationService(PerfumeSemanticSearchService search, JdbcRecommendationEvidenceRepository evidence,
            RecommendationPromptBuilder prompts, OllamaRecommendationClient llm, PerfumeRecommendationOptions options) {
        this.search = search; this.evidence = evidence; this.prompts = prompts; this.llm = llm; this.options = options;
        var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(options.maxConcurrent(), options.maxConcurrent(), 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
                    var thread = new Thread(task, "perfume-recommendation-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public PerfumeRecommendationResponse recommend(PerfumeSemanticSearchRequest request) {
        long started = System.nanoTime(); var progress = new Progress();
        try { return execute(request, started, progress); }
        catch (RecommendationFailure failure) {
            log.warn("Perfume recommendation failed: stage={}, code={}, elapsedMs={}", progress.stage, failure.code(), (System.nanoTime() - started) / 1_000_000);
            throw failure;
        }
    }
    private PerfumeRecommendationResponse execute(PerfumeSemanticSearchRequest request, long started, Progress progress) {
        OllamaRecommendationClient.checkInterrupted();
        Future<PerfumeRecommendationResponse> work;
        try { work = executor.submit(() -> run(request, progress)); }
        catch (RejectedExecutionException busy) { throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_BUSY); }
        long totalDeadline = started + options.requestTimeout().toNanos();
        while (true) {
            long now = System.nanoTime();
            long deadline = Math.min(totalDeadline, progress.generationDeadline);
            if (now >= deadline) {
                work.cancel(true);
                throw new RecommendationFailure(progress.generationDeadline < totalDeadline ? RecommendationFailure.Code.LLM_TIMEOUT : RecommendationFailure.Code.RECOMMEND_TIMEOUT);
            }
            try {
                var response = work.get(Math.min(deadline - now, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (System.nanoTime() >= totalDeadline) { work.cancel(true); throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_TIMEOUT); }
                return response;
            }
            catch (TimeoutException waiting) { /* Inspect stage/total deadline, without retrying any operation. */ }
            catch (InterruptedException stop) {
                work.cancel(true); Thread.currentThread().interrupt(); throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_INTERRUPTED);
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof RecommendationFailure known) { throw known; }
                if (failure.getCause() instanceof SemanticSearchFailure searchFailure) { throw fromSearch(searchFailure); }
                throw new RecommendationFailure(RecommendationFailure.Code.GENERATION_FAILED);
            }
        }
    }
    private PerfumeRecommendationResponse run(PerfumeSemanticSearchRequest request, Progress progress) {
        var result = search.search(request);
        OllamaRecommendationClient.checkInterrupted();
        if (result.results().isEmpty()) { return PerfumeRecommendationResponse.of("현재 검색된 임베딩 근거가 없어 향수를 추천할 수 없습니다.", result.results()); }
        progress.stage = "evidence";
        var snapshots = evidence.find(result.results()); // The read-only transaction ends before prompt generation or LLM I/O.
        OllamaRecommendationClient.checkInterrupted(); progress.stage = "prompt";
        var prompt = prompts.build(request.query(), snapshots);
        OllamaRecommendationClient.checkInterrupted(); progress.stage = "generation";
        progress.generationDeadline = System.nanoTime() + options.generationTimeout().toNanos();
        var generated = llm.generate(prompt);
        if (System.nanoTime() >= progress.generationDeadline) { throw new RecommendationFailure(RecommendationFailure.Code.LLM_TIMEOUT); }
        // Keep the deadline until the generation call really returns, even if the caller already cancelled its Future.
        OllamaRecommendationClient.checkInterrupted(); progress.generationDeadline = Long.MAX_VALUE; progress.stage = "validation";
        return PerfumeRecommendationResponse.of(prompts.validateAndRender(generated, prompt), result.results());
    }
    private static RecommendationFailure fromSearch(SemanticSearchFailure failure) {
        return new RecommendationFailure(switch (failure.code()) {
            case INVALID_REQUEST -> RecommendationFailure.Code.INVALID_REQUEST;
            case SEARCH_BUSY -> RecommendationFailure.Code.RECOMMEND_BUSY;
            case SEARCH_TIMEOUT, OLLAMA_TIMEOUT -> RecommendationFailure.Code.SEARCH_TIMEOUT;
            case SEARCH_INTERRUPTED -> RecommendationFailure.Code.RECOMMEND_INTERRUPTED;
            case MODEL_MISMATCH -> RecommendationFailure.Code.MODEL_MISMATCH;
            case DATABASE_UNAVAILABLE -> RecommendationFailure.Code.DATABASE_UNAVAILABLE;
            case DATABASE_TIMEOUT -> RecommendationFailure.Code.DATABASE_TIMEOUT;
            default -> RecommendationFailure.Code.SEARCH_FAILED;
        });
    }
    @Override public void close() { executor.shutdownNow(); }
}
