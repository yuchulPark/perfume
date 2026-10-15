package com.perfume.ai.recommend;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import com.perfume.ai.search.PerfumeSemanticSearchResponse.Hit;

final class RecommendationTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final EmbeddingModelIdentity EMBEDDING = new EmbeddingModelIdentity("qwen3-embedding:0.6b", "a".repeat(64), "perfume-v1");
    static PerfumeRecommendationOptions options() { return options(300000, 420000); }
    static PerfumeRecommendationOptions options(long generationMs, long totalMs) {
        return new PerfumeRecommendationOptions("qwen3:4b-instruct", "b".repeat(64), URI.create("http://ollama:11434"),
                Duration.ofMillis(3000), Duration.ofMillis(180000), Duration.ofMillis(generationMs), Duration.ofMillis(totalMs), 5, 1, 128, 0.2, 1, 2560);
    }
    static Hit hit(long id) { return new Hit(id, "Stored " + id, "Stored Brand", 1.0 / id); }
    static String text(long id) {
        return "Name: Stored " + id + "\nBrand: Stored Brand\nDescription: Stored fragrance description.\nTOP notes: Bergamot, Lemon\nMIDDLE notes: Rose\nBASE notes: Cedar\nAccord: citrus (percentage: 42)\nMetric performance.longevity: score: 8; scale: 10\nMetric performance.sillage: category: moderate\nMetric usage.season.summer: score: 0.7";
    }
    static RecommendationEvidence evidence(long id) { return evidence(id, text(id)); }
    static RecommendationEvidence evidence(long id, String text) { return new RecommendationEvidence((int) id, hit(id), text, Instant.parse("2026-10-16T00:00:00Z")); }
    static RecommendationPromptBuilder builder() { return builder(1); }
    static RecommendationPromptBuilder builder(int candidates) { return new RecommendationPromptBuilder(MAPPER, new RecommendationEvidenceFormatter(), options().inputBudgetBytes(), candidates); }
    static RecommendationPromptBuilder.Prompt prompt() { return builder().build("여름 향수", List.of(evidence(1))); }
    static String generated(RecommendationPromptBuilder.Prompt prompt) {
        var identity = prompt.allowed().stream().filter(RecommendationEvidenceFormatter.Sentence::identity).findFirst().orElseThrow();
        return pick(identity.source(), 1);
    }
    static String pick(int source, int factId) { return "{\"picks\":[{\"source\":" + source + ",\"factId\":" + factId + "}]}"; }
    static String jsonAnswer(String answer) {
        try { return MAPPER.writeValueAsString(java.util.Map.of("answer", answer)); }
        catch (JsonProcessingException failure) { throw new AssertionError(failure); }
    }
}
