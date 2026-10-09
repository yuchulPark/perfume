package com.perfume.scentrev.service;

/** Safe brand-level failure and any known discovery/commit progress, without exception cause chains. */
public record ScentRevCatalogBrandFailure(
        String brandSlug,
        int brandPosition,
        int totalBrands,
        String errorCategory,
        String message,
        ScentRevBrandDiscoveryResult discovery,
        int successfullyProcessedCount) {
}
