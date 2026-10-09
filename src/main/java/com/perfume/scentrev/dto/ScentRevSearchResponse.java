package com.perfume.scentrev.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Minimal search_fragrances output contract; rankings, cursors and presentation payloads are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevSearchResponse(List<Fragrance> results) {
    public ScentRevSearchResponse { results = List.copyOf(results); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fragrance(String fragranceSlug, String publicId, String name, String brandName, String brandSlug) { }
}
