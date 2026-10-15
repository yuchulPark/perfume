package com.perfume.ai.recommend;

import java.util.List;
import com.perfume.ai.search.PerfumeSemanticSearchResponse.Hit;

public record PerfumeRecommendationResponse(String answer, List<Hit> sources, int returnedCount) {
    public PerfumeRecommendationResponse { sources = List.copyOf(sources); }
    public static PerfumeRecommendationResponse of(String answer, List<Hit> sources) {
        return new PerfumeRecommendationResponse(answer, sources, sources.size());
    }
    @Override public String toString() { return "PerfumeRecommendationResponse[returnedCount=" + returnedCount + "]"; }
}
