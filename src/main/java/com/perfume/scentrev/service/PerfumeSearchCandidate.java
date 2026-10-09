package com.perfume.scentrev.service;

/** Display data only; candidates are never automatically imported. */
public record PerfumeSearchCandidate(String fragranceSlug, String publicId, String name,
        String brandName, String brandSlug, boolean cached, Source source) {
    public enum Source { LOCAL, SCENTREV }
}
