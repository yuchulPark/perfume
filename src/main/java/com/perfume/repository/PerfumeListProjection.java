package com.perfume.repository;

/** Scalar catalog rows do not load full descriptions or any entity relationships. */
public interface PerfumeListProjection {
    Long getId();
    String getPublicId();
    String getFragranceSlug();
    String getName();
    Integer getReleaseYear();
    String getImageUrl();
    Long getBrandId();
    String getBrandName();
    String getBrandSlug();
}
