package com.perfume.api.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** Only detached values; no JPA entity, lazy proxy, or source-history collection crosses the API boundary. */
public record PerfumeDetailResponse(Long id, String publicId, String fragranceSlug, String name,
        BrandResponse brand, String description, Integer releaseYear, String imageUrl, Long reviewsCount,
        NotesResponse notes, List<AccordResponse> accords, List<PerfumerResponse> perfumers,
        Map<String, MetricResponse> metrics, List<OpinionResponse> pros, List<OpinionResponse> cons,
        List<SimilarityResponse> similarFragrances) {
    public record NotesResponse(List<NoteResponse> top, List<NoteResponse> middle,
            List<NoteResponse> base, List<NoteResponse> unlayered) { }
    public record NoteResponse(Long id, String name, Integer position) { }
    public record AccordResponse(Long id, String name, Integer percentage, BigDecimal score, Integer position) { }
    public record PerfumerResponse(Long id, String publicId, String name, String company,
            String biography, Long perfumesCount) { }
    public record MetricResponse(BigDecimal score, String category, Long nRecords, String reliability,
            String scale, String derivedFrom, Long reviewsCount, String label, JsonNode details) { }
    public record OpinionResponse(String text, String source, Integer position,
            BigDecimal likeRatio, Long nRecords, JsonNode details) { }
    public record SimilarityResponse(String publicId, String fragranceSlug, String name,
            BigDecimal likeRatio, Long nRecords, Integer position, JsonNode details) { }
}
