package com.perfume.search;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ElasticsearchQuery {
    private static final List<String> FIELDS = List.of("name^8", "brandName^5", "topNotes^2", "middleNotes^2",
            "baseNotes^2", "unlayeredNotes^2", "accords^2", "description");

    static String body(ObjectMapper mapper, int page, int size, String query, String brand) {
        // Literal query text, never query_string syntax. Synonyms and fuzzy terms have separate branches.
        var should = List.of(
                Map.of("multi_match", Map.of("query", query, "fields", FIELDS, "type", "cross_fields", "operator", "and")),
                Map.of("multi_match", Map.of("query", query, "fields", List.of("name.ko^8", "brandName.ko^5",
                        "topNotes.ko^2", "middleNotes.ko^2", "baseNotes.ko^2", "unlayeredNotes.ko^2", "accords.ko^2", "description.ko"),
                        "type", "cross_fields", "operator", "and")),
                Map.of("multi_match", Map.of("query", query, "fields", List.of("name^8", "brandName^5"),
                        "type", "phrase", "boost", 2)),
                Map.of("multi_match", Map.of("query", query, "fields", FIELDS, "analyzer", "catalog_plain",
                        "fuzziness", "AUTO", "prefix_length", 1, "max_expansions", 25, "operator", "and", "boost", 0.3)));
        var bool = Map.of("should", should, "minimum_should_match", 1,
                "filter", brand == null ? List.of() : List.of(Map.of("term", Map.of("brandSlug", brand))));
        try {
            return mapper.writeValueAsString(Map.of("from", (long) page * size, "size", size,
                    "track_total_hits", true, "timeout", "800ms", "_source", false,
                    "sort", List.of(Map.of("_score", "desc"), Map.of("perfumeId", "asc")), "query", Map.of("bool", bool)));
        } catch (JsonProcessingException impossible) { throw new IllegalStateException("Cannot encode search query"); }
    }
}
