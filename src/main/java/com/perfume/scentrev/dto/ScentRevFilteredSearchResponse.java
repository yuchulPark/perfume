package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Only identifiers and the observed offset pagination contract, never ranking metrics/cursors. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevFilteredSearchResponse(
        List<Fragrance> results,
        Boolean truncated,
        Integer offset,
        Integer limit,
        Integer totalReturned,
        Boolean partial) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Fragrance(String fragranceSlug, String publicId, String name, String brandSlug) { }
}
