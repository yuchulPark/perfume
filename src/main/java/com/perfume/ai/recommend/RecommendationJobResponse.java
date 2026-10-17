package com.perfume.ai.recommend;

public record RecommendationJobResponse(String jobId, State state, PerfumeRecommendationResponse result, String errorCode, Integer errorStatus) {
    public enum State {
        RUNNING, CANCELLING, CANCELLED, SUCCEEDED, FAILED, TIMED_OUT;
        public boolean terminal() { return this != RUNNING && this != CANCELLING; }
    }
    @Override public String toString() { return "RecommendationJobResponse[state=" + state + "]"; }
}
