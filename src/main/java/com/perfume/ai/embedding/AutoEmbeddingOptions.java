package com.perfume.ai.embedding;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.core.env.Environment;

public record AutoEmbeddingOptions(Path stateDirectory, Duration maxRuntime) {
    public static final String PREFIX = EmbeddingBatchOptions.PREFIX + "auto.";
    public static final String ENABLED = PREFIX + "enabled";

    public AutoEmbeddingOptions {
        if (stateDirectory == null || !stateDirectory.isAbsolute() || maxRuntime == null
                || maxRuntime.compareTo(Duration.ofSeconds(1)) < 0 || maxRuntime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Invalid automatic embedding state directory or runtime limit.");
        }
    }

    public static AutoEmbeddingOptions from(Environment env) {
        try {
            return new AutoEmbeddingOptions(Path.of(env.getRequiredProperty(PREFIX + "state-directory")),
                    Duration.ofSeconds(env.getProperty(PREFIX + "max-runtime-seconds", Long.class, 3600L)));
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid automatic embedding settings.");
        }
    }
}
