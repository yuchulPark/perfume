package com.perfume.ai.embedding;

import java.util.List;
import java.util.Optional;

public interface PerfumeEmbeddingStore {
    Session open();

    record Window(long upperId, long available) { }

    /** One dedicated connection owns the advisory lock and all reads/writes for this batch. */
    interface Session extends AutoCloseable {
        Window window(long afterId);
        List<Long> nextIds(long afterId, long upperId, int limit);
        PerfumeEmbeddingDocument load(long id);
        Optional<String> findHash(long id, EmbeddingModelIdentity model);
        boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector);
        @Override void close();
    }
}
