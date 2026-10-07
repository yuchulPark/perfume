package com.perfume.scentrev.service;

/** Successful processing includes both new and reused rows; the mapper does not distinguish them. */
public record ScentRevBrandImportResult(
        ScentRevBrandDiscoveryResult discovery,
        int successfullyProcessedCount) {
}
