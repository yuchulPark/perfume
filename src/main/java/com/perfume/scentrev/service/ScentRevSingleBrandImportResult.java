package com.perfume.scentrev.service;

/** Persistence counts include committed accepted profiles only; discovery/fetched counts also include foreign results. */
public record ScentRevSingleBrandImportResult(String brandName, String providerBrandName, String brandSlug,
        int discoveryPages, int pagesCommitted, int rawResults, int duplicateDiscoveryCount,
        int uniqueFragrancesDiscovered, Long providerReportedTotal, int profilesFetched, int skippedForeignBrand,
        int perfumesInserted, int perfumesUpdated, int perfumesUnchanged,
        int perfumersProcessed, int notesProcessed, int accordsProcessed,
        int perfumePerfumerLinks, int perfumeNoteLinks, int perfumeAccordLinks,
        int totalMcpCalls, boolean completed, String stoppedStage, Integer stoppedOffset, String stoppedFragranceSlug) {
    public int perfumesProcessed() { return perfumesInserted + perfumesUpdated + perfumesUnchanged; }
}
