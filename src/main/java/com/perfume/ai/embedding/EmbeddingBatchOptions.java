package com.perfume.ai.embedding;

import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;

public record EmbeddingBatchOptions(EmbeddingModelIdentity model, URI baseUrl, int pageSize,
        int maxItems, long afterId, long delayMs, Duration connectTimeout, Duration readTimeout) {
    public static final String PREFIX = "perfume.ai.embedding.";
    public static final String ENABLED = PREFIX + "batch.enabled";

    public EmbeddingBatchOptions {
        if (model == null || baseUrl == null || !java.util.Set.of("http", "https").contains(baseUrl.getScheme())
                || baseUrl.getHost() == null || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || !java.util.Set.of("", "/").contains(baseUrl.getPath())) {
            throw new IllegalArgumentException("Use an Ollama base URL without credentials, query or path.");
        }
        if (pageSize < 1 || pageSize > 100 || maxItems < 1 || maxItems > 10000 || afterId < 0
                || delayMs < 0 || delayMs > 60000 || connectTimeout == null || readTimeout == null
                || connectTimeout.toMillis() < 1 || connectTimeout.toMillis() > 30000
                || readTimeout.toMillis() < 1 || readTimeout.toMillis() > 300000) {
            throw new IllegalArgumentException("Invalid embedding batch limits or HTTP timeouts.");
        }
    }

    public static EmbeddingBatchOptions from(Environment env) {
        return new EmbeddingBatchOptions(new EmbeddingModelIdentity(
                env.getProperty(PREFIX + "model", "qwen3-embedding:0.6b"),
                env.getProperty(PREFIX + "model-digest"), EmbeddingModelIdentity.TEXT_VERSION),
                parseUrl(env.getProperty(PREFIX + "base-url", "http://ollama:11434")),
                env.getProperty(PREFIX + "batch.page-size", Integer.class, 25),
                env.getProperty(PREFIX + "batch.max-items", Integer.class, 1000),
                env.getProperty(PREFIX + "batch.after-id", Long.class, 0L),
                env.getProperty(PREFIX + "batch.delay-ms", Long.class, 250L),
                Duration.ofMillis(env.getProperty(PREFIX + "connect-timeout-ms", Long.class, 3000L)),
                Duration.ofMillis(env.getProperty(PREFIX + "read-timeout-ms", Long.class, 180000L)));
    }

    private static URI parseUrl(String value) {
        try { return URI.create(value); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid Ollama base URL."); }
    }
}
