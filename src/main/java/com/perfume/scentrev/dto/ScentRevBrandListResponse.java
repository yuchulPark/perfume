package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** The observed list_brands identifiers, reported counts and offset metadata; cursors are ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevBrandListResponse(
        List<CatalogBrand> brands,
        Boolean truncated,
        Integer offset,
        Integer limit,
        Integer totalReturned) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CatalogBrand(String brandSlug, String brandName, Long fragranceCount) {
        /** Preserve callers that provide only identifiers; an absent count is unknown, not zero. */
        public CatalogBrand(String brandSlug, String brandName) {
            this(brandSlug, brandName, null);
        }
    }
}
