package com.perfume.ai.recommend;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import com.perfume.ai.transport.RecommendationCancellation;
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
    private final ScheduledExecutorService maintenance;
    private final RecommendationJobOptions jobOptions;
    private final LongSupplier ticker;
    private final java.util.Map<String, Job> jobs = new LinkedHashMap<>();
    private final java.util.Map<String, Job> tokens = new java.util.HashMap<>();
    private final java.util.Set<Job> active = new HashSet<>();
    private boolean closed;
    private static class Progress { volatile String stage = "search"; volatile long generationDeadline = Long.MAX_VALUE; }
    private static class Job {
        final String id = UUID.randomUUID().toString();
        final String tokenHash;
        final boolean retained;
        final long deadline;
        final Progress progress = new Progress();
        final RecommendationCancellation cancellation = new RecommendationCancellation();
        final CompletableFuture<PerfumeRecommendationResponse> outcome = new CompletableFuture<>();
        RecommendationJobResponse.State state = RecommendationJobResponse.State.RUNNING;
        RecommendationFailure.Code stopReason;
        RecommendationFailure.Code failure;
        PerfumeRecommendationResponse result;
        long finishedAt, storedBytes;
        Job(String tokenHash, boolean retained, long deadline) { this.tokenHash = tokenHash; this.retained = retained; this.deadline = deadline; }
    }
    public PerfumeRecommendationService(PerfumeSemanticSearchService search, JdbcRecommendationEvidenceRepository evidence,
            RecommendationPromptBuilder prompts, OllamaRecommendationClient llm, PerfumeRecommendationOptions options) {
        this(search, evidence, prompts, llm, options, RecommendationJobOptions.defaults());
    }
    public PerfumeRecommendationService(PerfumeSemanticSearchService search, JdbcRecommendationEvidenceRepository evidence,
            RecommendationPromptBuilder prompts, OllamaRecommendationClient llm, PerfumeRecommendationOptions options, RecommendationJobOptions jobs) {
        this(search, evidence, prompts, llm, options, jobs, System::nanoTime);
    }
    PerfumeRecommendationService(PerfumeSemanticSearchService search, JdbcRecommendationEvidenceRepository evidence,
            RecommendationPromptBuilder prompts, OllamaRecommendationClient llm, PerfumeRecommendationOptions options, RecommendationJobOptions jobs, LongSupplier ticker) {
        this.search = search; this.evidence = evidence; this.prompts = prompts; this.llm = llm; this.options = options; this.jobOptions = jobs; this.ticker = ticker;
        var sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(options.maxConcurrent(), options.maxConcurrent(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(options.maxConcurrent()), task -> {
                    var thread = new Thread(task, "perfume-recommendation-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        // Admission below rejects every additional job while resources are active. The bounded queue
        // only permits handing off after finally released admission, before the pool worker returns.
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> { var thread = new Thread(task, "perfume-recommendation-maintenance"); thread.setDaemon(true); return thread; });
        maintenance.scheduleWithFixedDelay(this::maintain, 100, 100, TimeUnit.MILLISECONDS);
    }
    public PerfumeRecommendationResponse recommend(PerfumeSemanticSearchRequest request) {
        OllamaRecommendationClient.checkInterrupted();
        var job = submit(request, null, false);
        try { return job.outcome.get(); }
        catch (InterruptedException stop) { requestStop(job, RecommendationFailure.Code.RECOMMEND_INTERRUPTED); Thread.currentThread().interrupt(); throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_INTERRUPTED); }
        catch (ExecutionException failure) { throw new RecommendationFailure(failure.getCause() instanceof RecommendationFailure known ? known.code() : RecommendationFailure.Code.GENERATION_FAILED); }
    }
    public RecommendationJobResponse start(PerfumeSemanticSearchRequest request, String token) { return snapshot(submit(request, tokenHash(token), true)); }
    public synchronized RecommendationJobResponse status(String id, String token) { prune(); return snapshot(owned(id, tokenHash(token))); }
    public synchronized RecommendationJobResponse find(String token) {
        prune(); var job = tokens.get(tokenHash(token)); if (job == null) throw missing(); return snapshot(job);
    }
    public RecommendationJobResponse cancel(String id, String token) {
        Job job;
        synchronized (this) { prune(); job = owned(id, tokenHash(token)); }
        requestStop(job, RecommendationFailure.Code.RECOMMEND_INTERRUPTED);
        return snapshot(job);
    }
    private synchronized Job submit(PerfumeSemanticSearchRequest request, String tokenHash, boolean retained) {
        prune();
        if (retained && tokens.containsKey(tokenHash)) return tokens.get(tokenHash); // Idempotent retry after a lost create response.
        if (closed || active.size() >= options.maxConcurrent()) throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_BUSY);
        var job = new Job(tokenHash, retained, ticker.getAsLong() + options.requestTimeout().toNanos());
        active.add(job);
        if (retained) { jobs.put(job.id, job); tokens.put(tokenHash, job); }
        try { executor.execute(() -> executeJob(job, request)); }
        catch (RejectedExecutionException busy) { active.remove(job); remove(job); throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_BUSY); }
        return job;
    }
    private void executeJob(Job job, PerfumeSemanticSearchRequest request) {
        PerfumeRecommendationResponse result = null; RecommendationFailure.Code failure = null;
        try (var binding = job.cancellation.bind()) {
            OllamaRecommendationClient.checkInterrupted();
            result = run(request, job.progress);
        } catch (RecommendationFailure known) { failure = known.code(); }
        catch (SemanticSearchFailure known) { failure = fromSearch(known).code(); }
        catch (Throwable unexpected) { failure = RecommendationFailure.Code.GENERATION_FAILED; }
        finally {
            if (failure != null && isTimeout(failure)) requestStop(job, failure);
            job.cancellation.awaitChildren();
            // This finalization, not Future.isDone/cancel, proves the local computation and child search ended.
            finish(job, result, failure);
        }
    }
    private synchronized void finish(Job job, PerfumeRecommendationResponse result, RecommendationFailure.Code failure) {
        if (job.stopReason == null && ticker.getAsLong() >= job.deadline) job.stopReason = RecommendationFailure.Code.RECOMMEND_TIMEOUT;
        if (job.stopReason != null) {
            job.failure = job.stopReason;
            job.state = isTimeout(job.stopReason) ? RecommendationJobResponse.State.TIMED_OUT : RecommendationJobResponse.State.CANCELLED;
        } else if (failure != null) { job.failure = failure; job.state = isTimeout(failure) ? RecommendationJobResponse.State.TIMED_OUT : RecommendationJobResponse.State.FAILED; }
        else {
            job.storedBytes = storedBytes(result);
            if (job.storedBytes + 2048 > jobOptions.maxStoredBytes()) { job.storedBytes = 0; job.failure = RecommendationFailure.Code.GENERATION_FAILED; job.state = RecommendationJobResponse.State.FAILED; }
            else { job.result = result; job.state = RecommendationJobResponse.State.SUCCEEDED; }
        }
        job.finishedAt = ticker.getAsLong(); active.remove(job);
        if (job.failure != null) {
            log.warn("Perfume recommendation finished: state={}, code={}", job.state, job.failure);
            if (!job.retained) job.outcome.completeExceptionally(new RecommendationFailure(job.failure));
        } else if (!job.retained) job.outcome.complete(job.result);
        prune();
    }
    private void requestStop(Job job, RecommendationFailure.Code reason) {
        synchronized (this) {
            if (job.state.terminal() || job.stopReason != null) return;
            job.stopReason = reason; job.state = RecommendationJobResponse.State.CANCELLING;
            // Synchronous callers retain their existing deadline response; admission stays occupied until finish.
            if (!job.retained) job.outcome.completeExceptionally(new RecommendationFailure(reason));
        }
        job.cancellation.cancel();
    }
    private void maintain() {
        Job[] running;
        synchronized (this) { running = active.toArray(Job[]::new); prune(); }
        for (var job : running) {
            if (ticker.getAsLong() >= Math.min(job.deadline, job.progress.generationDeadline))
                requestStop(job, job.progress.generationDeadline < job.deadline ? RecommendationFailure.Code.LLM_TIMEOUT : RecommendationFailure.Code.RECOMMEND_TIMEOUT);
        }
    }
    private synchronized RecommendationJobResponse snapshot(Job job) {
        var code = job.failure == null ? job.stopReason : job.failure;
        Integer status = code == null ? null : Integer.valueOf(code.status().value());
        return new RecommendationJobResponse(job.id, job.state, job.result, code == null ? null : code.name(), status);
    }
    private Job owned(String id, String hash) {
        if (id == null || id.length() != 36) throw missing();
        var job = jobs.get(id);
        if (job == null || !MessageDigest.isEqual(job.tokenHash.getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) throw missing();
        return job;
    }
    private void prune() {
        long now = ticker.getAsLong();
        var completed = jobs.values().stream().filter(job -> job.state.terminal()).toList();
        int count = completed.size(); long bytes = completed.stream().mapToLong(job -> job.storedBytes + 2048).sum();
        for (var job : completed) {
            if (now - job.finishedAt >= TimeUnit.MILLISECONDS.toNanos(jobOptions.retentionMs()) || count > jobOptions.maxCompleted() || bytes > jobOptions.maxStoredBytes()) {
                remove(job); count--; bytes -= job.storedBytes + 2048;
            }
        }
    }
    private void remove(Job job) { if (job.retained) { jobs.remove(job.id); tokens.remove(job.tokenHash); } }
    private static long storedBytes(PerfumeRecommendationResponse result) {
        if (result == null) return 0;
        return result.answer().length() * 2L + result.sources().stream().mapToLong(source -> 256L + source.name().length() * 2L + (source.brandName() == null ? 0 : source.brandName().length() * 2L)).sum();
    }
    private static boolean isTimeout(RecommendationFailure.Code code) { return code.name().endsWith("_TIMEOUT"); }
    private static RecommendationFailure missing() { return new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND); }
    private static String tokenHash(String token) {
        if (token == null || token.length() != 64 || !token.matches("[0-9a-f]{64}")) throw missing();
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable."); }
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
        progress.generationDeadline = ticker.getAsLong() + options.generationTimeout().toNanos();
        var generated = llm.generate(prompt);
        if (ticker.getAsLong() >= progress.generationDeadline) { throw new RecommendationFailure(RecommendationFailure.Code.LLM_TIMEOUT); }
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
    @Override public void close() {
        Job[] running;
        synchronized (this) { closed = true; running = active.toArray(Job[]::new); jobs.clear(); tokens.clear(); }
        for (var job : running) requestStop(job, RecommendationFailure.Code.RECOMMEND_INTERRUPTED);
        maintenance.shutdownNow(); executor.shutdown(); // Cancelled handoffs must still run their finally blocks.
    }
    synchronized int retainedJobCount() { return jobs.size(); }
}
