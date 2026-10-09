package com.perfume.scentrev.curated;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.perfume.scentrev.service.*;

/** Optional bootstrap orchestration; no discovery dependency, startup job or run-wide transaction. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ScentRevCuratedBrandImportService {
    public static final int DEFAULT_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 5;
    private final ScentRevCuratedBrandConfiguration configuration;
    private final ScentRevCatalogRunProgressService progress;
    private final ScentRevCatalogBatchImportService batches;

    public ScentRevCuratedBrandImportService(ScentRevCuratedBrandConfiguration configuration,
            ScentRevCatalogRunProgressService progress, ScentRevCatalogBatchImportService batches) {
        this.configuration = configuration;
        this.progress = progress;
        this.batches = batches;
    }

    public CuratedRun createCuratedRun() {
        var catalog = configuration.load();
        var selected = catalog.verifiedBrands();
        if (selected.isEmpty()) { throw new IllegalStateException("No verified enabled curated brands; approve the mapping before creating a run."); }
        // This existing immutable snapshot carrier performs no discovery or API calls.
        var run = progress.saveSnapshot(new ScentRevCatalogBrandDiscoveryResult(selected.size(), 0, 0, selected));
        return new CuratedRun(catalog, run);
    }

    /** Failures stop this invocation, are recorded in the returned result, and are never retried here. */
    public ScentRevCatalogBatchImportResult processNextBatch(Long runId, int size) {
        validateBatchSize(size);
        return batches.processNextBatchStoppingOnFailure(runId, size);
    }

    public ScentRevCatalogBatchImportResult processSmallSmokeBatch(Long runId, String verifiedSlug) {
        return batches.processSelectedBatchStoppingOnFailure(runId, java.util.List.of(verifiedSlug));
    }

    public static void validateBatchSize(int size) {
        if (size < 1 || size > MAX_BATCH_SIZE) { throw new IllegalArgumentException("Curated batch size must be between 1 and 5 brands."); }
    }
    public record CuratedRun(CuratedBrandCatalog catalog, ScentRevCatalogRunSummary run) { }
}
