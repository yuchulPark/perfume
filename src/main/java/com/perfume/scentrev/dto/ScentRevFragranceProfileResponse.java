package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Provider domain data only; missing sections remain null and presentation data is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ScentRevFragranceProfileResponse(
        ScentRevIdentity identity,
        ScentRevAccords accords,
        List<ScentRevPerfumer> perfumers,
        ScentRevNotePyramid notePyramid,
        ScentRevPerformance performance,
        ScentRevPriceValue priceValue,
        ScentRevProsCons prosCons,
        ScentRevRemindsOf remindsOf) {
}
