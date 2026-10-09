package com.perfume.scentrev.service;

/** Safe stop report; provider payloads, credentials, and underlying persistence exceptions are not attached. */
public class ScentRevSingleBrandImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final ScentRevSingleBrandImportResult result;
    public ScentRevSingleBrandImportException(ScentRevSingleBrandImportResult result, String diagnostic) {
        super("Single-brand import stopped at " + result.stoppedStage() + ", offset "
                + result.stoppedOffset() + ": " + diagnostic + " Previously committed pages remain committed.");
        this.result = result;
    }
    public ScentRevSingleBrandImportResult getResult() { return result; }
}
