package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevPerfumer(
        String name,
        String company,
        String biography,
        String perfumerId,
        Long perfumesCount,
        List<PortfolioFragrance> otherFragrances) {

    /** Portfolio ratings use the same metric shape as full-profile identity ratings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PortfolioFragrance(
            String name,
            String publicId,
            String brandName,
            String fragranceSlug,
            ScentRevReliableMetric rating) {
    }
}
