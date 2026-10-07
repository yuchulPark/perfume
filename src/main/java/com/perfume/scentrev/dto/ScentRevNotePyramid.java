package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Also supports standalone get_note_pyramid data, whose fragrance name may be null. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevNotePyramid(
        List<String> top,
        List<String> middle,
        List<String> base,
        String publicId,
        String brandSlug,
        String fragranceName,
        String fragranceSlug) {
}
