package com.perfume.ai.recommend;

import java.time.Instant;
import com.perfume.ai.search.PerfumeSemanticSearchResponse.Hit;

/** Embedding-time snapshot, never a claim that public catalog facts are current. */
public record RecommendationEvidence(int sourceNumber, Hit source, String sourceText, Instant snapshotAt) {
    @Override public String toString() { return "RecommendationEvidence[sourceNumber=" + sourceNumber + ", perfumeId=" + source.perfumeId() + "]"; }
}
