package com.perfume.ai.embedding;

import java.util.List;
import java.util.Optional;

public interface PerfumeEmbeddingStore {
    Session open();

    record Window(long upperId, long available) { }

    /** One dedicated connection owns the advisory lock and all reads/writes for this batch. */
    interface Session extends AutoCloseable {
        Window window(long afterId);
        /** Fixed automatic scan range; implementations may optimize this bounded count. */
        default Window window(long afterId, long upperId) {
            long count = 0, cursor = afterId;
            while (true) {
                var ids = nextIds(cursor, upperId, 100);
                if (ids.isEmpty()) { return new Window(upperId, count); }
                count += ids.size();
                cursor = ids.get(ids.size() - 1);
            }
        }
        List<Long> nextIds(long afterId, long upperId, int limit);
        PerfumeEmbeddingDocument load(long id);
        Optional<String> findHash(long id, EmbeddingModelIdentity model);
        boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector);
        @Override void close();
    }
}
