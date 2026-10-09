package com.perfume.scentrev.curated;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;

import com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType;
import com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;

/** Validated, immutable source/parent mapping. Every raw occurrence has exactly one parent. */
public record CuratedBrandCatalog(List<String> rawLabels, List<CuratedBrandDefinition> parents) {
    public CuratedBrandCatalog {
        rawLabels = List.copyOf(rawLabels);
        parents = List.copyOf(parents);
        var keys = new HashSet<String>();
        var positions = new HashSet<Integer>();
        for (var parent : parents) {
            if (blank(parent.canonicalBrandKey()) || !keys.add(parent.canonicalBrandKey())
                    || blank(parent.canonicalDisplayName()) || parent.canonicalDisplayName().length() > 255
                    || parent.verificationStatus() == null || parent.sources().isEmpty()) {
                throw new IllegalArgumentException("Curated parents require distinct keys, names, statuses and source entries.");
            }
            if (parent.brandSlug() != null && (parent.brandSlug().length() > 255
                    || !parent.brandSlug().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || blank(parent.verificationEvidence()) || parent.verificationStatus() == VerificationStatus.UNRESOLVED)) {
                throw new IllegalArgumentException("A curated slug requires explicit verification evidence; unresolved slugs are prohibited.");
            }
            if (parent.verificationStatus() == VerificationStatus.VERIFIED && blank(parent.brandSlug())) {
                throw new IllegalArgumentException("Verified curated parents require a verified canonical slug.");
            }
            if (parent.reportedFragranceCount() != null && parent.reportedFragranceCount() < 0) {
                throw new IllegalArgumentException("Reported fragrance counts must be nonnegative or unknown.");
            }
            for (var source : parent.sources()) {
                int index = source.sourceIndex();
                if (index < 1 || index > rawLabels.size() || !positions.add(index)
                        || !rawLabels.get(index - 1).equals(source.rawLabel()) || source.entryType() == null) {
                    throw new IllegalArgumentException("Every raw label occurrence must map exactly once without changing its text.");
                }
            }
        }
        if (positions.size() != rawLabels.size()) {
            throw new IllegalArgumentException("The curated mapping must preserve every raw source occurrence.");
        }
    }

    /** First configured verified slug wins; collections/aliases cannot add another import. */
    public List<CatalogBrand> verifiedBrands() {
        var brands = new LinkedHashMap<String, CatalogBrand>();
        parents.stream().filter(CuratedBrandDefinition::importable).forEach(parent ->
                brands.putIfAbsent(parent.brandSlug(), new CatalogBrand(parent.brandSlug(),
                        parent.canonicalDisplayName(), parent.reportedFragranceCount())));
        return List.copyOf(brands.values());
    }

    public List<CuratedBrandDefinition> unresolvedParents() {
        return parents.stream().filter(parent -> parent.enabled()
                && parent.verificationStatus() == VerificationStatus.UNRESOLVED).toList();
    }

    public int disabledCount() {
        return (int) parents.stream().filter(parent -> !parent.enabled()
                || parent.verificationStatus() == VerificationStatus.DISABLED).count();
    }

    public long entryCount(EntryType type) {
        return parents.stream().flatMap(parent -> parent.sources().stream()).filter(source -> source.entryType() == type).count();
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
