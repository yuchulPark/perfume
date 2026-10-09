package com.perfume.scentrev.service;

import java.util.List;

import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;

/** Immutable canonical brands in first-seen order; no fixed provider catalog size. */
public record ScentRevCatalogBrandDiscoveryResult(
        int rawBrandCount,
        int duplicateBrandCount,
        int pageCount,
        List<CatalogBrand> brands) {

    public ScentRevCatalogBrandDiscoveryResult {
        brands = List.copyOf(brands);
    }

    public int uniqueBrandCount() { return brands.size(); }
}
