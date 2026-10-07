package com.perfume.scentrev.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Dedicated get_appreciation response, separate from full-profile identity rating. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevAppreciationResponse(
        String name,
        String publicId,
        String brandSlug,
        AppreciationMetric appreciation,
        String fragranceSlug) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AppreciationMetric(BigDecimal score, String category, Long nRecords, String reliability) {
    }
}
