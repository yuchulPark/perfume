package com.perfume.ai.embedding;

import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;

/** Detached snapshot of existing public catalog rows; no JPA entity or schema generation. */
public record PerfumeEmbeddingDocument(long id, String name, String brand, String description,
        Integer releaseYear, Long reviewsCount, List<Note> notes, List<Accord> accords, List<Metric> metrics) {
    public record Note(String layer, String name, Integer position) { }
    public record Accord(String name, Integer percentage, BigDecimal score, Integer position) { }
    public record Metric(String key, BigDecimal score, String category, Long records, String reliability,
            String scale, String derivedFrom, Long reviewsCount, String label, JsonNode details) { }
}
