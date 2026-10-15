package com.perfume.scentrev.preview;

import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;

/** Counts describe list candidates, never profile-verified brand membership or guaranteed billing. */
public record ScentRevBrandPreviewResult(String brandName, String brandSlug, long dbPerfumeCount,
        int rawCandidateCount, int candidateCount, int duplicateCount, int foreignBrandCandidateRows,
        int pagesFetched, int listApiCalls, int nextOffset, boolean completed, Boolean hasMore,
        StopReason stopReason, FailureType failureType, Long retryAfterMs) {
    public enum StopReason { COMPLETED, MAX_PAGES, API_BUDGET, API_ERROR, INVALID_PAGE, INTERRUPTED }

    public long basicImportCallsForObservedCandidates() { return (long) pagesFetched + candidateCount; }
    public Long estimatedFullImportCalls() { return completed ? basicImportCallsForObservedCandidates() : null; }
    /** 71 / 60 is one operator-observed example, not a provider tariff or a transferable guarantee. */
    public long referenceCallsAssumingAllObservedCandidatesNew() { return (candidateCount * 71L + 59) / 60; }
    public boolean failed() {
        return stopReason == StopReason.API_ERROR || stopReason == StopReason.INVALID_PAGE || stopReason == StopReason.INTERRUPTED;
    }
}
