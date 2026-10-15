package com.perfume.repository;

/** Detached scalar identity only; no full perfume payload or lazy Brand entity. */
public interface PerfumeImportIdentityProjection {
    Long getId();
    String getPublicId();
    String getFragranceSlug();
    Long getBrandId();
    String getBrandSlug();
}
