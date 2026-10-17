package com.perfume.ai.recommend;

import org.springframework.core.env.Environment;

public record RecommendationJobOptions(long retentionMs, int maxCompleted, long maxStoredBytes) {
    public RecommendationJobOptions {
        if (retentionMs < 1000 || retentionMs > 3_600_000 || maxCompleted < 1 || maxCompleted > 256
                || maxStoredBytes < 65536 || maxStoredBytes > 16_777_216) throw new IllegalArgumentException("Invalid recommendation job retention limits.");
    }
    public static RecommendationJobOptions defaults() { return new RecommendationJobOptions(600_000, 64, 2_097_152); }
    public static RecommendationJobOptions from(Environment env) {
        String prefix = "perfume.ai.recommend.jobs.";
        return new RecommendationJobOptions(env.getProperty(prefix + "retention-ms", Long.class, 600_000L),
                env.getProperty(prefix + "max-completed", Integer.class, 64), env.getProperty(prefix + "max-stored-bytes", Long.class, 2_097_152L));
    }
}
