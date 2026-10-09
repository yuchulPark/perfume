package com.perfume.scentrev.service;

import java.util.List;

/** In-memory run summary. Fragrance totals include known progress in failed brands. */
public record ScentRevCatalogImportResult(
        ScentRevCatalogBrandDiscoveryResult discovery,
        List<ScentRevBrandImportResult> successfulBrands,
        List<ScentRevCatalogBrandFailure> failedBrands) {

    public ScentRevCatalogImportResult {
        successfulBrands = List.copyOf(successfulBrands);
        failedBrands = List.copyOf(failedBrands);
    }

    public int rawBrandCount() { return discovery.rawBrandCount(); }
    public int uniqueBrandCount() { return discovery.uniqueBrandCount(); }
    public int duplicateBrandCount() { return discovery.duplicateBrandCount(); }
    public int brandsAttempted() { return brandsSucceeded() + brandsFailed(); }
    public int brandsSucceeded() { return successfulBrands.size(); }
    public int brandsFailed() { return failedBrands.size(); }

    public long totalFragrancesDiscovered() {
        return successfulBrands.stream().mapToLong(brand -> brand.discovery().discoveredResultCount()).sum()
                + failedBrands.stream().filter(brand -> brand.discovery() != null)
                        .mapToLong(brand -> brand.discovery().discoveredResultCount()).sum();
    }

    /** Sum of per-brand deduplicated counts; no fuzzy/global identity merging is performed. */
    public long totalUniqueFragrances() {
        return successfulBrands.stream().mapToLong(brand -> brand.discovery().uniqueFragranceCount()).sum()
                + failedBrands.stream().filter(brand -> brand.discovery() != null)
                        .mapToLong(brand -> brand.discovery().uniqueFragranceCount()).sum();
    }

    public long totalFragrancesProcessed() {
        return successfulBrands.stream().mapToLong(ScentRevBrandImportResult::successfullyProcessedCount).sum()
                + failedBrands.stream().mapToLong(ScentRevCatalogBrandFailure::successfullyProcessedCount).sum();
    }
}
