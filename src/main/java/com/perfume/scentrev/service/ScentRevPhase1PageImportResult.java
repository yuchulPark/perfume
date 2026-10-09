package com.perfume.scentrev.service;

import java.util.Set;

/** Committed page counts; master identities support distinct totals across pages. Links include reused rows. */
public record ScentRevPhase1PageImportResult(int perfumesInserted, int perfumesUpdated, int perfumesUnchanged,
        Set<String> perfumerIds, Set<String> noteNames, Set<String> accordNames,
        int perfumePerfumerLinks, int perfumeNoteLinks, int perfumeAccordLinks) {
    public ScentRevPhase1PageImportResult {
        perfumerIds = Set.copyOf(perfumerIds);
        noteNames = Set.copyOf(noteNames);
        accordNames = Set.copyOf(accordNames);
    }
    public int perfumesProcessed() { return perfumesInserted + perfumesUpdated + perfumesUnchanged; }
}
