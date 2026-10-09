package com.perfume.api.dto;

public record PerfumeSummaryResponse(Long id, String publicId, String fragranceSlug, String name,
        Integer releaseYear, String imageUrl, BrandResponse brand) { }
