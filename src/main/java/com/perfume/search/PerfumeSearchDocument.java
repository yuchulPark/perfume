package com.perfume.search;

import java.util.List;

/** A disposable projection of public catalog data; never an authoritative entity. */
public record PerfumeSearchDocument(long perfumeId, String name, String brandName, String brandSlug,
        String description, Integer releaseYear, List<String> topNotes, List<String> middleNotes,
        List<String> baseNotes, List<String> unlayeredNotes, List<String> accords) { }
