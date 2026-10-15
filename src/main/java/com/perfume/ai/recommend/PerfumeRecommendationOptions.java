package com.perfume.ai.recommend;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import com.perfume.ai.search.PerfumeSemanticSearchOptions;
import org.springframework.core.env.Environment;

public record PerfumeRecommendationOptions(String model, String modelDigest, URI baseUrl, Duration connectTimeout,
        Duration readTimeout, Duration generationTimeout, Duration requestTimeout, int dbTimeoutSeconds,
        int maxConcurrent, int maxOutputTokens, double temperature, int maxEvidenceCandidates, int inputBudgetBytes) {
    public static final String PREFIX = "perfume.ai.recommend.";
    public static final String ENABLED = PREFIX + "enabled";
    public static final int CONTEXT_TOKENS = 4096;
    public PerfumeRecommendationOptions {
        if (!"qwen3:4b-instruct".equals(model)) { throw new IllegalArgumentException("Use the configured local instruction model qwen3:4b-instruct."); }
        modelDigest = EmbeddingModelIdentity.normalizeDigest(modelDigest);
        if (baseUrl == null || !Set.of("http", "https").contains(baseUrl.getScheme()) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !Set.of("", "/").contains(baseUrl.getPath())) {
            throw new IllegalArgumentException("Use a local generation base URL without credentials, query or path.");
        }
        if (!bounded(connectTimeout, 10000) || !bounded(readTimeout, 300000) || !bounded(generationTimeout, 300000)
                || !bounded(requestTimeout, 600000) || dbTimeoutSeconds < 1 || dbTimeoutSeconds > 10
                || maxConcurrent < 1 || maxConcurrent > 2 || maxOutputTokens < 128 || maxOutputTokens > 768
                || !Double.isFinite(temperature) || temperature < 0 || temperature > 0.5
                || maxEvidenceCandidates < 1 || maxEvidenceCandidates > 2 || inputBudgetBytes < 1024
                || inputBudgetBytes > CONTEXT_TOKENS - 768 - 512) {
            throw new IllegalArgumentException("Invalid recommendation timeouts, concurrency or generation limits.");
        }
    }
    public static PerfumeRecommendationOptions from(Environment env) {
        if (!env.getProperty(PerfumeSemanticSearchOptions.ENABLED, Boolean.class, false)) {
            throw new IllegalArgumentException("Recommendations require perfume.ai.search.enabled=true.");
        }
        PerfumeSemanticSearchOptions.from(env); // Also checks embedding identity and rejects all manual job modes.
        URI url;
        try { url = URI.create(env.getProperty(PREFIX + "base-url", "http://ollama:11434")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid generation base URL."); }
        return new PerfumeRecommendationOptions(env.getProperty(PREFIX + "model", "qwen3:4b-instruct"),
                env.getProperty(PREFIX + "model-digest"), url, duration(env, "connect-timeout-ms", 3000),
                duration(env, "read-timeout-ms", 180000), duration(env, "generation-timeout-ms", 300000),
                duration(env, "request-timeout-ms", 420000), env.getProperty(PREFIX + "db-timeout-seconds", Integer.class, 5),
                env.getProperty(PREFIX + "max-concurrent", Integer.class, 1), env.getProperty(PREFIX + "max-output-tokens", Integer.class, 128),
                env.getProperty(PREFIX + "temperature", Double.class, 0.2), env.getProperty(PREFIX + "max-evidence-candidates", Integer.class, 1),
                env.getProperty(PREFIX + "max-input-bytes", Integer.class, 2560));
    }
    /** Fixed, safe labels for diagnostics, independent of user/provider text. */
    public String optimizationMode() { return maxEvidenceCandidates == 1 ? "compact" : "two-candidate"; }
    private static Duration duration(Environment env, String key, long fallback) { return Duration.ofMillis(env.getProperty(PREFIX + key, Long.class, fallback)); }
    private static boolean bounded(Duration duration, long max) { return duration != null && duration.toMillis() > 0 && duration.toMillis() <= max; }
}
