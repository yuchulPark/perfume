package com.perfume.scentrev.preview;

import org.springframework.core.env.Environment;

/** A single explicit brand, with two independent caps on attempted list calls. */
public record ScentRevBrandPreviewOptions(String brandSlug, int maxPages, int apiCallBudget) {
    public static final String PREFIX = "scentrev.brand-preview";

    public ScentRevBrandPreviewOptions {
        if (!canonicalSlug(brandSlug) || "all".equals(brandSlug)) {
            throw new IllegalArgumentException("Preview requires exactly one canonical brand-slug; lists, all and wildcards are prohibited.");
        }
        if (maxPages < 1 || maxPages > 100 || apiCallBudget < 0 || apiCallBudget > 100) {
            throw new IllegalArgumentException("Preview max-pages must be 1..100 and api-call-budget must be 0..100.");
        }
    }

    public static ScentRevBrandPreviewOptions from(Environment environment) {
        if (environment.containsProperty(PREFIX + ".brand-slugs")) {
            throw new IllegalArgumentException("Preview accepts brand-slug only, never brand-slugs.");
        }
        try {
            int pages = Integer.parseInt(environment.getProperty(PREFIX + ".max-pages", "5"));
            int budget = Integer.parseInt(environment.getProperty(PREFIX + ".api-call-budget", Integer.toString(pages)));
            return new ScentRevBrandPreviewOptions(environment.getProperty(PREFIX + ".brand-slug"), pages, budget);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Preview limits must be integers.");
        }
    }

    static boolean canonicalSlug(String value) {
        return value != null && value.length() <= 255 && value.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }
}
