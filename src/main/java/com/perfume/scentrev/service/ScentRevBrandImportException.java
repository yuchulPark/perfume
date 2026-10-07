package com.perfume.scentrev.service;

/** Safe progress diagnostic: no provider/SQL payload, raw cause, credentials or configuration. */
public class ScentRevBrandImportException extends RuntimeException {

    public enum Stage { DISCOVERY, PROFILE, IDENTITY, PERSISTENCE }

    private final Stage stage;
    private final ScentRevBrandDiscoveryResult discovery;
    private final int successfullyProcessedCount;
    private final String failedFragranceSlug;
    private final Integer failedOffset;

    private ScentRevBrandImportException(Stage stage, ScentRevBrandDiscoveryResult discovery,
                                        int processed, String slug, Integer offset, String reason) {
        super(stage == Stage.DISCOVERY
                ? "Failed discovering brand " + discovery.brandSlug() + " at offset " + offset
                        + " after " + discovery.pageCount() + " pages (" + discovery.discoveredResultCount()
                        + " rows, " + discovery.uniqueFragranceCount() + " unique): " + reason
                : "Failed importing brand " + discovery.brandSlug() + " fragrance " + (processed + 1)
                        + "/" + discovery.uniqueFragranceCount() + ": " + slug + " (" + stage
                        + ", " + processed + " successfully processed; " + discovery.discoveredResultCount()
                        + " discovered rows, " + discovery.duplicateDiscoveryCount() + " duplicates). " + reason);
        this.stage = stage;
        this.discovery = discovery;
        this.successfullyProcessedCount = processed;
        this.failedFragranceSlug = slug;
        this.failedOffset = offset;
    }

    static ScentRevBrandImportException discovery(ScentRevBrandDiscoveryResult progress, int offset, String reason) {
        return new ScentRevBrandImportException(Stage.DISCOVERY, progress, 0, null, offset, reason);
    }

    static ScentRevBrandImportException importing(Stage stage, ScentRevBrandDiscoveryResult discovery,
                                                int processed, String slug, String reason) {
        return new ScentRevBrandImportException(stage, discovery, processed, slug, null, reason);
    }

    public Stage getStage() { return stage; }
    public ScentRevBrandDiscoveryResult getDiscovery() { return discovery; }
    public int getSuccessfullyProcessedCount() { return successfullyProcessedCount; }
    public String getFailedFragranceSlug() { return failedFragranceSlug; }
    public Integer getFailedOffset() { return failedOffset; }
}
