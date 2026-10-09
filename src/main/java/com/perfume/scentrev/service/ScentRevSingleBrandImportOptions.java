package com.perfume.scentrev.service;

/** Exactly one explicit canonical provider slug and a sequential request delay. */
public record ScentRevSingleBrandImportOptions(String brandSlug, long delayMs) {
    public static final long DEFAULT_DELAY_MS = 250;
    public static final long MIN_DELAY_MS = 100;
    public ScentRevSingleBrandImportOptions {
        if (brandSlug == null || brandSlug.isBlank() || brandSlug.length() > 255
                || !brandSlug.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || "all".equals(brandSlug)) {
            throw new IllegalArgumentException("Set scentrev.brand-import.brand-slug to exactly one verified provider slug; lists, all, and wildcards are prohibited.");
        }
        if (delayMs < MIN_DELAY_MS) {
            throw new IllegalArgumentException("scentrev.brand-import.delay-ms must be at least 100.");
        }
    }
    public static ScentRevSingleBrandImportOptions parse(String slug, String delay) {
        long millis;
        try { millis = Long.parseLong(delay == null ? Long.toString(DEFAULT_DELAY_MS) : delay); }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("scentrev.brand-import.delay-ms must be an integer of at least 100.");
        }
        return new ScentRevSingleBrandImportOptions(slug, millis);
    }
}
