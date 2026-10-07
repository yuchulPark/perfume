package com.perfume.scentrev.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** The matching field shape used by identity rating, longevity, and sillage. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevReliableMetric(
        BigDecimal score,
        String category,
        Long nRecords,
        String reliability) {
}
