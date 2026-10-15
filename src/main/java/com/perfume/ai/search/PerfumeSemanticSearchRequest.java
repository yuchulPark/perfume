package com.perfume.ai.search;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/** Strict request-local JSON handling; existing catalog/ObjectMapper coercion settings are unchanged. */
public record PerfumeSemanticSearchRequest(String query, Integer topK) {
    public static final int MAX_QUERY_LENGTH = 500;
    public PerfumeSemanticSearchRequest {
        if (query == null || query.length() > MAX_QUERY_LENGTH
                || query.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) { invalid(); }
        topK = topK == null ? 5 : topK;
        if (topK < 1 || topK > 20) { invalid(); }
    }
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PerfumeSemanticSearchRequest fromJson(JsonNode root) {
        if (root == null || !root.isObject() || !root.path("query").isTextual()) { invalid(); }
        var fields = root.fieldNames();
        while (fields.hasNext()) { if (!java.util.Set.of("query", "topK").contains(fields.next())) { invalid(); } }
        var count = root.path("topK");
        if (!count.isMissingNode() && !count.isNull() && (!count.isIntegralNumber() || !count.canConvertToInt())) { invalid(); }
        return new PerfumeSemanticSearchRequest(root.path("query").textValue(), count.isIntegralNumber() ? count.intValue() : null);
    }
    @Override public String toString() { return "PerfumeSemanticSearchRequest[topK=" + topK + "]"; }
    private static void invalid() { throw new SemanticSearchFailure(SemanticSearchFailure.Code.INVALID_REQUEST); }
}
