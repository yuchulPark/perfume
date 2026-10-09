package com.perfume.scentrev.service;

import java.time.Instant;
import com.perfume.scentrev.progress.CatalogImportRunStatus;

public record ScentRevCatalogRunSummary(Long runId, CatalogImportRunStatus status, int totalBrands,
        long totalReportedFragrances, int brandsWithUnknownCount, int brandsCompleted, int brandsFailed,
        int brandsPending, int brandsRunning, long processedFragrances, boolean batchActive,
        Instant createdAt, Instant startedAt, Instant finishedAt, Instant lastUpdatedAt) {
    public boolean runComplete() { return status == CatalogImportRunStatus.COMPLETED; }
}
