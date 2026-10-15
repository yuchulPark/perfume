package com.perfume.ai.search;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import org.springframework.core.env.Environment;

public record PerfumeSemanticSearchOptions(EmbeddingModelIdentity model, URI baseUrl, Duration connectTimeout,
        Duration readTimeout, Duration requestTimeout, int dbTimeoutSeconds, int maxConcurrent) {
    public static final String PREFIX = "perfume.ai.search.";
    public static final String ENABLED = PREFIX + "enabled";
    public PerfumeSemanticSearchOptions {
        if (model == null || baseUrl == null || !Set.of("http", "https").contains(baseUrl.getScheme()) || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null || baseUrl.getFragment() != null
                || !Set.of("", "/").contains(baseUrl.getPath())) {
            throw new IllegalArgumentException("Use a local embedding base URL without credentials, query or path.");
        }
        if (!bounded(connectTimeout, 10000) || !bounded(readTimeout, 60000) || !bounded(requestTimeout, 120000)
                || dbTimeoutSeconds < 1 || dbTimeoutSeconds > 10 || maxConcurrent < 1 || maxConcurrent > 2) {
            throw new IllegalArgumentException("Invalid semantic search timeouts or concurrency limit.");
        }
    }
    public static PerfumeSemanticSearchOptions from(Environment env) {
        for (var manual : List.of("scentrev.brand-import", "scentrev.brand-preview", "perfume.ai.embedding.batch.enabled")) {
            if (env.getProperty(manual, Boolean.class, false)) { throw new IllegalArgumentException("Run semantic search separately from manual Import, Preview and embedding jobs."); }
        }
        URI baseUrl;
        try { baseUrl = URI.create(env.getProperty(PREFIX + "base-url", "http://ollama:11434")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid semantic search base URL."); }
        return new PerfumeSemanticSearchOptions(new EmbeddingModelIdentity(env.getProperty(PREFIX + "model", "qwen3-embedding:0.6b"),
                env.getProperty(PREFIX + "model-digest"), env.getProperty(PREFIX + "text-version", EmbeddingModelIdentity.TEXT_VERSION)),
                baseUrl, duration(env, "connect-timeout-ms", 3000), duration(env, "read-timeout-ms", 30000),
                duration(env, "request-timeout-ms", 45000), env.getProperty(PREFIX + "db-timeout-seconds", Integer.class, 5),
                env.getProperty(PREFIX + "max-concurrent", Integer.class, 1));
    }
    private static Duration duration(Environment env, String key, long fallback) { return Duration.ofMillis(env.getProperty(PREFIX + key, Long.class, fallback)); }
    private static boolean bounded(Duration duration, long maximum) { return duration != null && duration.toMillis() > 0 && duration.toMillis() <= maximum; }
}
