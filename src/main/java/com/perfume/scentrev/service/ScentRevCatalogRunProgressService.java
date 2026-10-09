package com.perfume.scentrev.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.scentrev.progress.*;

/** Database-only short transactions. Every mutation locks the run row, including claims and recovery. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class ScentRevCatalogRunProgressService {
    public static final int MAX_BATCH_BRANDS = 100;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private final CatalogImportRunRepository runs;
    private final CatalogImportBrandProgressRepository brands;

    public ScentRevCatalogRunProgressService(CatalogImportRunRepository runs, CatalogImportBrandProgressRepository brands) {
        this.runs = runs;
        this.brands = brands;
    }

    public ScentRevCatalogRunSummary saveSnapshot(ScentRevCatalogBrandDiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "Catalog discovery is required.");
        long reported = 0;
        int unknown = 0;
        var seen = new HashSet<String>();
        for (var brand : discovery.brands()) {
            validateSlug(brand.brandSlug());
            if (!seen.add(brand.brandSlug())) { throw new IllegalArgumentException("Snapshot contains duplicate canonical brands."); }
            if (brand.fragranceCount() == null) { unknown++; }
            else if (brand.fragranceCount() < 0) { throw new IllegalArgumentException("Reported fragrance counts cannot be negative."); }
            else { reported = Math.addExact(reported, brand.fragranceCount()); }
        }
        Instant now = Instant.now();
        var run = runs.save(new CatalogImportRun(discovery.uniqueBrandCount(), reported, unknown, now));
        var snapshot = new ArrayList<CatalogImportBrandProgress>();
        int position = 0;
        for (var brand : discovery.brands()) {
            snapshot.add(new CatalogImportBrandProgress(run, brand.brandSlug(), brand.brandName(),
                    brand.fragranceCount(), ++position, now));
        }
        brands.saveAllAndFlush(snapshot);
        return summarize(run);
    }

    public BatchClaim claimBatch(Long runId, int maxBrands, Collection<String> selectedSlugs) {
        validateBatchSize(maxBrands);
        var run = lockRun(runId);
        requireInactive(run);
        if (brands.countByImportRun_IdAndStatus(runId, CatalogImportBrandStatus.RUNNING) != 0) {
            throw new IllegalStateException("Interrupted running work requires explicit recovery before another batch.");
        }
        List<CatalogImportBrandProgress> selected;
        if (selectedSlugs == null) {
            selected = brands.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(runId,
                    CatalogImportBrandStatus.PENDING, PageRequest.of(0, maxBrands));
        } else {
            if (selectedSlugs.isEmpty() || selectedSlugs.size() > maxBrands
                    || new HashSet<>(selectedSlugs).size() != selectedSlugs.size()) {
                throw new IllegalArgumentException("A bounded set of distinct snapshot brand slugs is required.");
            }
            for (String slug : selectedSlugs) {
                validateSlug(slug);
                if (!brands.existsByImportRun_IdAndBrandSlug(runId, slug)) {
                    throw new IllegalArgumentException("Selected brand does not belong to this run snapshot.");
                }
            }
            selected = brands.findByImportRun_IdAndStatusAndBrandSlugInOrderByCatalogPositionAsc(runId,
                    CatalogImportBrandStatus.PENDING, selectedSlugs, PageRequest.of(0, maxBrands));
        }
        if (selected.isEmpty()) {
            return new BatchClaim(runId, null, run.getTotalBrands(), List.of(), refresh(run));
        }
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        run.beginBatch(token, now);
        selected.forEach(brand -> brand.start(now));
        brands.saveAllAndFlush(selected);
        var work = selected.stream().map(brand -> new BrandWork(brand.getId(), brand.getBrandSlug(),
                brand.getBrandName(), brand.getReportedFragranceCount(), brand.getCatalogPosition())).toList();
        return new BatchClaim(runId, token, run.getTotalBrands(), work, refresh(run));
    }

    public void checkClaim(Long runId, String token, Long progressId) {
        requireOwnedBrand(lockOwned(runId, token), progressId);
    }

    public void completeBrand(Long runId, String token, Long progressId, long processed) {
        var run = lockOwned(runId, token);
        var brand = requireOwnedBrand(run, progressId);
        brand.complete(processed, Instant.now());
        brands.saveAndFlush(brand);
        refresh(run);
    }

    /** Only fixed application diagnostics may be passed here, never a raw exception message. */
    public void failBrand(Long runId, String token, Long progressId, long processed, String safeMessage) {
        var run = lockOwned(runId, token);
        var brand = requireOwnedBrand(run, progressId);
        brand.fail(processed, safeMessage, Instant.now());
        brands.saveAndFlush(brand);
        refresh(run);
    }

    public ScentRevCatalogRunSummary finishBatch(Long runId, String token) {
        var run = lockOwned(runId, token);
        if (brands.countByImportRun_IdAndStatus(runId, CatalogImportBrandStatus.RUNNING) != 0) {
            throw new IllegalStateException("Batch has unfinished running brands; explicit recovery is required.");
        }
        run.releaseBatch(Instant.now());
        return refresh(run);
    }

    /** Owned executor has stopped on a failure; release only its unattempted reservations. */
    public ScentRevCatalogRunSummary stopBatch(Long runId, String token) {
        var run = lockOwned(runId, token);
        requeue(runId, CatalogImportBrandStatus.RUNNING);
        run.releaseBatch(Instant.now());
        return refresh(run);
    }

    /** Explicit recovery only AFTER the previous executor has stopped; no timer/automatic takeover. */
    public ScentRevCatalogRunSummary recoverInterruptedRun(Long runId) {
        var run = lockRun(runId);
        requeue(runId, CatalogImportBrandStatus.RUNNING);
        run.releaseBatch(Instant.now()); // Revokes the old token; late progress writes will be rejected.
        return refresh(run);
    }

    public ScentRevCatalogRunSummary retryFailedBrands(Long runId) {
        var run = lockRun(runId);
        requireInactive(run);
        requeue(runId, CatalogImportBrandStatus.FAILED);
        return refresh(run);
    }

    /** A short row lock keeps this multi-query summary consistent with progress writers. */
    public ScentRevCatalogRunSummary getRun(Long runId) { return summarize(lockRun(runId)); }

    private void requeue(Long runId, CatalogImportBrandStatus status) {
        var rows = brands.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(runId, status);
        Instant now = Instant.now();
        rows.forEach(brand -> brand.requeue(now));
        brands.saveAllAndFlush(rows);
    }

    private CatalogImportRun lockRun(Long runId) {
        if (runId == null || runId <= 0) { throw new IllegalArgumentException("A positive catalog run ID is required."); }
        return runs.lockById(runId).orElseThrow(() -> new IllegalArgumentException("Catalog run does not exist: " + runId));
    }

    private CatalogImportRun lockOwned(Long runId, String token) {
        var run = lockRun(runId);
        if (token == null || !token.equals(run.getActiveBatchToken())) {
            throw new IllegalStateException("Batch ownership was revoked; stop this executor and inspect the run.");
        }
        return run;
    }

    private CatalogImportBrandProgress requireOwnedBrand(CatalogImportRun run, Long progressId) {
        var brand = brands.findById(progressId).orElseThrow(() -> new IllegalStateException("Brand progress row is missing."));
        if (!run.getId().equals(brand.getImportRun().getId()) || brand.getStatus() != CatalogImportBrandStatus.RUNNING) {
            throw new IllegalStateException("Brand is not running in this claimed catalog run.");
        }
        return brand;
    }

    private static void requireInactive(CatalogImportRun run) {
        if (run.getActiveBatchToken() != null) {
            throw new IllegalStateException("Catalog run has an active batch; recover explicitly only after its executor has stopped.");
        }
    }

    private ScentRevCatalogRunSummary refresh(CatalogImportRun run) {
        int completed = count(run, CatalogImportBrandStatus.COMPLETED);
        int failed = count(run, CatalogImportBrandStatus.FAILED);
        int pending = count(run, CatalogImportBrandStatus.PENDING);
        int running = count(run, CatalogImportBrandStatus.RUNNING);
        run.updateTotals(completed, failed, pending, running, Instant.now());
        runs.save(run);
        return summary(run, completed, failed, pending, running);
    }

    private ScentRevCatalogRunSummary summarize(CatalogImportRun run) {
        return summary(run, count(run, CatalogImportBrandStatus.COMPLETED), count(run, CatalogImportBrandStatus.FAILED),
                count(run, CatalogImportBrandStatus.PENDING), count(run, CatalogImportBrandStatus.RUNNING));
    }

    private int count(CatalogImportRun run, CatalogImportBrandStatus status) {
        return Math.toIntExact(brands.countByImportRun_IdAndStatus(run.getId(), status));
    }

    private ScentRevCatalogRunSummary summary(CatalogImportRun run, int completed, int failed, int pending, int running) {
        return new ScentRevCatalogRunSummary(run.getId(), run.getStatus(), run.getTotalBrands(), run.getTotalReportedFragrances(),
                run.getBrandsWithUnknownCount(), completed, failed, pending, running, brands.sumProcessedFragrances(run.getId()),
                run.getActiveBatchToken() != null, run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt(), run.getLastUpdatedAt());
    }

    public static void validateBatchSize(int maxBrands) {
        if (maxBrands < 1 || maxBrands > MAX_BATCH_BRANDS) { throw new IllegalArgumentException("Batch size must be between 1 and 100 brands."); }
    }

    private static void validateSlug(String slug) {
        if (slug == null || slug.length() > 255 || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("A canonical snapshot brand slug is required.");
        }
    }

    public record BrandWork(Long progressId, String brandSlug, String brandName, Long reportedFragrances, int catalogPosition) { }
    public record BatchClaim(Long runId, String token, int totalBrands, List<BrandWork> brands, ScentRevCatalogRunSummary run) {
        public BatchClaim { brands = List.copyOf(brands); }
    }
}
