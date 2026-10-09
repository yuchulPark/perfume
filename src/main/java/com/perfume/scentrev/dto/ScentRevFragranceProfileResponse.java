package com.perfume.scentrev.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Typed existing fields plus lossless source JSON; optional sections remain nullable. */
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
        ScentRevRemindsOf remindsOf,
        JsonNode appreciation,
        JsonNode notes,
        @JsonIgnore JsonNode wearSummary,
        @JsonIgnore JsonNode rawResponse) {
    /** Preserve existing mapper/client callers that construct the original DTO. */
    public ScentRevFragranceProfileResponse(ScentRevIdentity identity, ScentRevAccords accords,
            List<ScentRevPerfumer> perfumers, ScentRevNotePyramid notePyramid, ScentRevPerformance performance,
            ScentRevPriceValue priceValue, ScentRevProsCons prosCons, ScentRevRemindsOf remindsOf) {
        this(identity, accords, perfumers, notePyramid, performance, priceValue, prosCons, remindsOf, null, null, null, null);
    }

    public ScentRevFragranceProfileResponse withRawResponse(JsonNode raw) {
        return new ScentRevFragranceProfileResponse(identity, accords, perfumers, notePyramid, performance,
                priceValue, prosCons, remindsOf, appreciation, notes, wearSummary, raw.deepCopy());
    }

    public ScentRevFragranceProfileResponse withWearSummary(JsonNode wear) {
        return new ScentRevFragranceProfileResponse(identity, accords, perfumers, notePyramid, performance,
                priceValue, prosCons, remindsOf, appreciation, notes, wear.deepCopy(), rawResponse);
    }

    public ScentRevFragranceProfileResponse withNotePyramid(ScentRevNotePyramid pyramid) {
        return new ScentRevFragranceProfileResponse(identity, accords, perfumers, pyramid, performance,
                priceValue, prosCons, remindsOf, appreciation, notes, wearSummary, rawResponse);
    }
}
