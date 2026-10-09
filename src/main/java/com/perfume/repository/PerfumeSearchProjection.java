package com.perfume.repository;

/** Scalar projection: local search never loads descriptions or lazy entity graphs. */
public interface PerfumeSearchProjection {
    String getFragranceSlug();
    String getPublicId();
    String getName();
    String getBrandName();
    String getBrandSlug();
}
