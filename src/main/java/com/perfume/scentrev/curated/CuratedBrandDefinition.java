package com.perfume.scentrev.curated;

import java.util.List;

/** Configuration metadata only: keys identify parents, and never imply provider slugs. */
public record CuratedBrandDefinition(String canonicalBrandKey, String canonicalDisplayName,
        String resolutionQuery, String brandSlug, boolean enabled, VerificationStatus verificationStatus,
        String verificationEvidence, Long reportedFragranceCount, String notes, List<SourceEntry> sources) {
    public CuratedBrandDefinition { sources = List.copyOf(sources); }
    public boolean importable() { return enabled && verificationStatus == VerificationStatus.VERIFIED; }
    public enum VerificationStatus { UNRESOLVED, VERIFIED, DISABLED }
    public enum EntryType { BRAND, COLLECTION, ALIAS, COLLABORATION, UNRESOLVED }
    public record SourceEntry(int sourceIndex, String rawLabel, EntryType entryType) { }
}
