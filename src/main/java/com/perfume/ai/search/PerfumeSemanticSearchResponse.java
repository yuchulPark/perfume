package com.perfume.ai.search;

import java.util.List;

public record PerfumeSemanticSearchResponse(List<Hit> results, int returnedCount) {
    public PerfumeSemanticSearchResponse { results = List.copyOf(results); }
    public static PerfumeSemanticSearchResponse of(List<Hit> results) { return new PerfumeSemanticSearchResponse(results, results.size()); }
    public record Hit(long perfumeId, String name, String brandName, double similarity) { }
}
