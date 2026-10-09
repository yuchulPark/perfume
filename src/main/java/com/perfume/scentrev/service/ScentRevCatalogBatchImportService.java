package com.perfume.scentrev.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.scentrev.service.ScentRevCatalogBatchImportResult.BrandFailure;
import com.perfume.scentrev.service.ScentRevCatalogRunProgressService.BatchClaim;

/** Persist one catalog plan; subsequent bounded executions reuse it and the unchanged brand importer. */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ScentRevCatalogBatchImportService {
    private static final Logger log = LoggerFactory.getLogger(ScentRevCatalogBatchImportService.class);
    private final ScentRevCatalogBrandDiscoveryService discovery;
    private final ScentRevCatalogRunProgressService progress;
    private final ScentRevBrandPhase1ImportService importer;

    public ScentRevCatalogBatchImportService(ScentRevCatalogBrandDiscoveryService discovery,
            ScentRevCatalogRunProgressService progress, ScentRevBrandPhase1ImportService importer) {
        this.discovery = discovery;
        this.progress = progress;
        this.importer = importer;
    }

    public ScentRevCatalogRunSummary createRun() {
        var result = progress.saveSnapshot(discovery.discoverBrands()); // All remote discovery finishes before this DB transaction.
        log.info("Catalog run {} planned: {} brands, {} reported fragrances, {} unknown counts", result.runId(),
                result.totalBrands(), result.totalReportedFragrances(), result.brandsWithUnknownCount());
        return result;
    }

    public ScentRevCatalogBatchImportResult processNextBatch(Long runId, int maxBrands) {
        requireNotInterrupted();
        return execute(progress.claimBatch(runId, maxBrands, null));
    }

    /** Optional explicit selection from the same persisted snapshot; completed/failed brands are not selected. */
    public ScentRevCatalogBatchImportResult processSelectedBatch(Long runId, Collection<String> slugs) {
        requireNotInterrupted();
        if (slugs == null) { throw new IllegalArgumentException("Selected snapshot brands are required."); }
        ScentRevCatalogRunProgressService.validateBatchSize(slugs.size());
        return execute(progress.claimBatch(runId, slugs.size(), List.copyOf(slugs)));
    }

    public ScentRevCatalogRunSummary getRun(Long runId) { return progress.getRun(runId); }
    public ScentRevCatalogRunSummary retryFailedBrands(Long runId) { return progress.retryFailedBrands(runId); }
    public ScentRevCatalogRunSummary recoverInterruptedRun(Long runId) { return progress.recoverInterruptedRun(runId); }

    /** Curated/manual safety: stop on every brand failure, including provider/auth/rate/access errors. */
    public ScentRevCatalogBatchImportResult processNextBatchStoppingOnFailure(Long runId, int maxBrands) {
        requireNotInterrupted();
        return execute(progress.claimBatch(runId, maxBrands, null), true);
    }

    public ScentRevCatalogBatchImportResult processSelectedBatchStoppingOnFailure(Long runId, Collection<String> slugs) {
        requireNotInterrupted();
        if (slugs == null) { throw new IllegalArgumentException("Selected snapshot brands are required."); }
        ScentRevCatalogRunProgressService.validateBatchSize(slugs.size());
        return execute(progress.claimBatch(runId, slugs.size(), List.copyOf(slugs)), true);
    }

    private ScentRevCatalogBatchImportResult execute(BatchClaim claim) { return execute(claim, false); }

    private ScentRevCatalogBatchImportResult execute(BatchClaim claim, boolean stopOnFailure) {
        log.info("Catalog run {} batch starting: {} selected; completed {}/{}", claim.runId(), claim.brands().size(),
                claim.run().brandsCompleted(), claim.totalBrands());
        int completed = 0;
        long processed = 0;
        long discovered = 0;
        var failures = new ArrayList<BrandFailure>();
        for (var brand : claim.brands()) {
            requireNotInterrupted(); // An interrupted/crashed batch is recovered explicitly, never silently resumed here.
            progress.checkClaim(claim.runId(), claim.token(), brand.progressId());
            log.info("Catalog run {} [{}/{}] Importing {} ({}): reported fragrances {}", claim.runId(), brand.catalogPosition(),
                    claim.totalBrands(), brand.brandName(), brand.brandSlug(), brand.reportedFragrances() == null ? "unknown" : brand.reportedFragrances());
            ScentRevBrandImportResult imported = null;
            BrandFailure failure = null;
            try {
                imported = importer.importBrand(brand.brandSlug());
                if (imported != null && imported.discovery() != null) { discovered += imported.discovery().uniqueFragranceCount(); }
                if (imported == null || imported.discovery() == null || !brand.brandSlug().equals(imported.discovery().brandSlug())
                        || imported.successfullyProcessedCount() != imported.discovery().uniqueFragranceCount()) {
                    failure = new BrandFailure(brand.brandSlug(), "INVALID_BRAND_RESULT: inconsistent importer result.", 0);
                }
            } catch (RuntimeException exception) {
                failure = safeFailure(brand.brandSlug(), exception);
                if (exception instanceof ScentRevBrandImportException safe && safe.getDiscovery() != null) {
                    discovered += safe.getDiscovery().uniqueFragranceCount();
                }
            }
            // Progress failures propagate and retain the claim for explicit recovery, rather than hiding a DB outage.
            if (failure == null) {
                long count = imported.successfullyProcessedCount();
                progress.completeBrand(claim.runId(), claim.token(), brand.progressId(), count);
                completed++;
                processed += count;
                log.info("Catalog run {} [{}/{}] Completed {}: {} processed", claim.runId(), brand.catalogPosition(),
                        claim.totalBrands(), brand.brandSlug(), count);
            } else {
                progress.failBrand(claim.runId(), claim.token(), brand.progressId(), failure.processedFragrances(), failure.message());
                failures.add(failure);
                processed += failure.processedFragrances();
                log.warn("Catalog run {} [{}/{}] Failed {}: {}", claim.runId(), brand.catalogPosition(), claim.totalBrands(),
                        brand.brandSlug(), failure.message());
                if (stopOnFailure) { break; }
            }
        }
        var run = claim.token() == null ? claim.run() : stopOnFailure && !failures.isEmpty()
                ? progress.stopBatch(claim.runId(), claim.token()) : progress.finishBatch(claim.runId(), claim.token());
        log.info("Catalog run {} batch observed {} unique fragrances discovered, {} processed; completed {}, failed {}, pending {}",
                claim.runId(), discovered, processed, run.brandsCompleted(), run.brandsFailed(), run.brandsPending());
        return new ScentRevCatalogBatchImportResult(claim.runId(), claim.brands().size(), completed, failures.size(), processed, run, failures);
    }

    private static BrandFailure safeFailure(String slug, RuntimeException exception) {
        if (exception instanceof ScentRevBrandImportException safe && safe.getDiscovery() != null
                && slug.equals(safe.getDiscovery().brandSlug())) {
            int count = safe.getSuccessfullyProcessedCount();
            return new BrandFailure(slug, safe.getStage().name() + ": brand import failed after " + count + " processed fragrances.", count);
        }
        return new BrandFailure(slug, "BRAND_IMPORT: brand import failed; earlier commits remain. Attempt progress unknown.", 0);
    }

    private static void requireNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Catalog batch interrupted; recover its running work after this executor stops.");
        }
    }
}
