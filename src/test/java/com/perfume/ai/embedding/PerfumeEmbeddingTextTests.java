package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class PerfumeEmbeddingTextTests {
    private final PerfumeEmbeddingText texts = new PerfumeEmbeddingText();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixesLayerPositionTieNameMetricAndJsonKeyOrderWithoutRounding() throws Exception {
        var a = rich(false);
        var b = rich(true);
        assertThat(texts.render(a)).isEqualTo(texts.render(b)).contains(
                "Name: 향수 Example", "Brand: Stored Brand", "Description: Stored description", "Release year: 2021",
                "TOP notes: Bergamot, Lemon", "MIDDLE notes: Rose", "BASE notes: Musk", "UNLAYERED notes: Tea",
                "Accord: citrus (percentage: 70; score: 0.70000000001)", "Accord: woody",
                "Metric performance.longevity", "Metric performance.sillage");
        assertThat(texts.render(a)).contains("stored details: {\"a\":{\"c\":1,\"d\":2},\"z\":[3,2]}");
        assertThat(texts.hash(texts.render(a))).isEqualTo(texts.hash(texts.render(b)));
    }

    @Test
    void omitsMissingDataAndNeverAssignsFlatNotesToAnInventedLayer() {
        var document = new PerfumeEmbeddingDocument(1, "Minimal", "Brand", null, null, null,
                List.of(new PerfumeEmbeddingDocument.Note("UNLAYERED", "Tea", null)), List.of(), List.of());
        assertThat(texts.render(document)).isEqualTo("Name: Minimal\nBrand: Brand\nUNLAYERED notes: Tea")
                .doesNotContain("TOP", "MIDDLE", "BASE", "null", "Release", "Metric");
    }

    @Test
    void sha256UsesKnownUtf8DigestAndDiffersWhenTextChanges() {
        assertThat(texts.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(texts.hash("향수")).matches("[0-9a-f]{64}").isEqualTo(texts.hash("향수"));
        assertThat(texts.hash("향수")).isNotEqualTo(texts.hash("향수2"));
    }

    @Test
    void relationAndEvaluationOnlyChangesAlterTheHash() throws Exception {
        var base = rich(false);
        var changedNote = new PerfumeEmbeddingDocument(base.id(), base.name(), base.brand(), base.description(), base.releaseYear(), base.reviewsCount(),
                List.of(new PerfumeEmbeddingDocument.Note("TOP", "Orange", 0)), base.accords(), base.metrics());
        var changedAccord = new PerfumeEmbeddingDocument(base.id(), base.name(), base.brand(), base.description(), base.releaseYear(), base.reviewsCount(),
                base.notes(), List.of(new PerfumeEmbeddingDocument.Accord("citrus", 71, new BigDecimal("0.7"), 0)), base.metrics());
        var changedMetric = new PerfumeEmbeddingDocument(base.id(), base.name(), base.brand(), base.description(), base.releaseYear(), base.reviewsCount(),
                base.notes(), base.accords(), List.of(metric("performance.longevity", "{\"new_stored_measure\":9}")));
        var hash = texts.hash(texts.render(base));
        for (var changed : List.of(changedNote, changedAccord, changedMetric)) {
            assertThat(texts.hash(texts.render(changed))).isNotEqualTo(hash);
        }
    }

    @Test
    void identityDescriptionYearAndReviewCountChangesAlterTheHash() {
        var base = PerfumeEmbeddingBatchTests.document(1, "Stored");
        var changed = List.of(
                PerfumeEmbeddingBatchTests.document(1, "Updated"),
                new PerfumeEmbeddingDocument(1, base.name(), "Other brand", null, null, null, List.of(), List.of(), List.of()),
                new PerfumeEmbeddingDocument(1, base.name(), base.brand(), "Stored description", null, null, List.of(), List.of(), List.of()),
                new PerfumeEmbeddingDocument(1, base.name(), base.brand(), null, 2021, null, List.of(), List.of(), List.of()),
                new PerfumeEmbeddingDocument(1, base.name(), base.brand(), null, null, 99L, List.of(), List.of(), List.of()));
        for (var document : changed) { assertThat(texts.hash(texts.render(document))).isNotEqualTo(texts.hash(texts.render(base))); }
    }

    private PerfumeEmbeddingDocument rich(boolean reversed) throws Exception {
        var notes = List.of(new PerfumeEmbeddingDocument.Note("BASE", "Musk", 0),
                new PerfumeEmbeddingDocument.Note("TOP", "Lemon", 0), new PerfumeEmbeddingDocument.Note("TOP", "Bergamot", 0),
                new PerfumeEmbeddingDocument.Note("MIDDLE", "Rose", 0), new PerfumeEmbeddingDocument.Note("UNLAYERED", "Tea", null));
        var accords = List.of(new PerfumeEmbeddingDocument.Accord("woody", null, null, null),
                new PerfumeEmbeddingDocument.Accord("citrus", 70, new BigDecimal("0.70000000001"), 0));
        var metrics = List.of(metric("performance.sillage", "{}"), metric("performance.longevity",
                reversed ? "{\"z\":[3.0,2],\"a\":{\"d\":2,\"c\":1.00}}" : "{\"a\":{\"c\":1,\"d\":2},\"z\":[3,2]}"));
        if (reversed) { notes = reverse(notes); accords = reverse(accords); metrics = reverse(metrics); }
        return new PerfumeEmbeddingDocument(1, "향수  Example", "Stored Brand", "Stored\n description", 2021, 99L, notes, accords, metrics);
    }

    private PerfumeEmbeddingDocument.Metric metric(String key, String json) throws Exception {
        return new PerfumeEmbeddingDocument.Metric(key, new BigDecimal("8.00"), "Stored category", 12L, "high", "0-10", null, null, null, mapper.readTree(json));
    }

    private static <T> List<T> reverse(List<T> rows) {
        var result = new java.util.ArrayList<>(rows);
        java.util.Collections.reverse(result);
        return result;
    }
}
