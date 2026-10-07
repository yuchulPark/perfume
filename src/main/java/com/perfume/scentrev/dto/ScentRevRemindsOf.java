package com.perfume.scentrev.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevRemindsOf(
        String publicId,
        String fragranceName,
        String fragranceSlug,
        List<RelatedFragrance> remindsOf) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RelatedFragrance(String name, String publicId, String fragranceSlug, BigDecimal likeRatio) {
    }
}
