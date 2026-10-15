package com.perfume.ai.embedding;

import java.util.Locale;

public record EmbeddingModelIdentity(String name, String digest, String textVersion) {
    public static final int DIMENSIONS = 1024;
    public static final String TEXT_VERSION = "perfume-v1";

    public EmbeddingModelIdentity {
        if (name == null || name.isBlank() || name.length() > 128 || !name.matches("[A-Za-z0-9._:/-]+")) {
            throw new IllegalArgumentException("A valid local embedding model name is required.");
        }
        digest = normalizeDigest(digest);
        if (!TEXT_VERSION.equals(textVersion)) {
            throw new IllegalArgumentException("Unsupported embedding text format version.");
        }
    }

    public static String normalizeDigest(String digest) {
        if (digest == null) { throw new IllegalArgumentException("The full installed Ollama model digest is required."); }
        var value = digest.toLowerCase(Locale.ROOT);
        if (value.matches("[0-9a-f]{64}")) { value = "sha256:" + value; }
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("The full installed Ollama model digest is required.");
        }
        return value;
    }
}
