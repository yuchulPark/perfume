package com.perfume.scentrev.service;

import java.util.List;

import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;

/** Stable first-seen order, with duplicates counted before profile requests. */
public record ScentRevBrandDiscoveryResult(
        String brandSlug,
        int pageCount,
        int discoveredResultCount,
        int duplicateDiscoveryCount,
        List<Fragrance> fragrances) {

    public ScentRevBrandDiscoveryResult {
        fragrances = List.copyOf(fragrances);
    }

    public int uniqueFragranceCount() {
        return fragrances.size();
    }
}
