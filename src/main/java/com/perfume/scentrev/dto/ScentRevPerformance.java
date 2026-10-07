package com.perfume.scentrev.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevPerformance(
        String name,
        String publicId,
        String brandSlug,
        String fragranceSlug,
        ScentRevReliableMetric longevity,
        ScentRevReliableMetric sillage,
        Projection projection,
        Season season,
        ScentRevScaledMetric timeOfDay) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Projection(
            BigDecimal score,
            String category,
            Long nRecords,
            String reliability,
            String derivedFrom) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Season(String scale, PrimarySeason primary, BySeason bySeason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PrimarySeason(String name, BigDecimal score, String category, Long nRecords) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BySeason(SeasonMetric spring, SeasonMetric summer, SeasonMetric fall, SeasonMetric winter) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SeasonMetric(BigDecimal score, String category, Long nRecords) {
    }
}
