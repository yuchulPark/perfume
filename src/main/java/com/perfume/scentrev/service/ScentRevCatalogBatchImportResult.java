package com.perfume.scentrev.service;

import java.util.List;

public record ScentRevCatalogBatchImportResult(Long runId, int brandsSelected, int brandsCompletedThisBatch,
        int brandsFailedThisBatch, long fragrancesProcessedThisBatch, ScentRevCatalogRunSummary run,
        List<BrandFailure> failedBrands) {
    public ScentRevCatalogBatchImportResult { failedBrands = List.copyOf(failedBrands); }
    public int totalRunCompleted() { return run.brandsCompleted(); }
    public int totalRunFailed() { return run.brandsFailed(); }
    public int totalRunPending() { return run.brandsPending(); }
    public boolean runComplete() { return run.runComplete(); }
    public record BrandFailure(String brandSlug, String message, long processedFragrances) { }
}
