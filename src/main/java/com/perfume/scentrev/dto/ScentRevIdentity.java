package com.perfume.scentrev.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevIdentity(
        String name,
        String publicId,
        String brandName,
        String brandSlug,
        String fragranceSlug,
        String description,
        Integer releaseYear,
        Long reviewsCount,
        ScentRevScaledMetric gender,
        ScentRevReliableMetric rating) {
}
