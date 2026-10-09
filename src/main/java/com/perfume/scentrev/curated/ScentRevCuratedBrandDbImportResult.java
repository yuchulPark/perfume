package com.perfume.scentrev.curated;

/** Counts for a single Brand-only seed; returned after the transactional operation succeeds. */
public record ScentRevCuratedBrandDbImportResult(int selectedDefinitions, int inserted,
        int updated, int unchanged) {
    public int processed() { return inserted + updated + unchanged; }
}
