package com.perfume.scentrev.curated;

import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.domain.Brand;
import com.perfume.repository.BrandRepository;

/** Explicit local Brand-table seed. No provider client, fragrance importer, or startup execution. */
@Service
public class ScentRevCuratedBrandDbImportService {
    private final ScentRevCuratedBrandConfiguration configuration;
    private final BrandRepository brands;

    public ScentRevCuratedBrandDbImportService(ScentRevCuratedBrandConfiguration configuration,
            BrandRepository brands) {
        this.configuration = configuration;
        this.brands = brands;
    }

    /**
     * Validate the complete selected input before any repository access, then seed in one transaction.
     * Existing managed rows retain their primary keys and slugs; only the curated display name is updated.
     * Database uniqueness also prevents duplicate slugs if concurrent writers race; failures roll back
     * the complete invocation, with no automatic retries.
     */
    @Transactional
    public ScentRevCuratedBrandDbImportResult importVerifiedBrands() {
        var selected = loadValidatedDefinitions();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        for (var definition : selected) {
            var existing = brands.findByBrandSlug(definition.brandSlug());
            if (existing.isEmpty()) {
                brands.save(new Brand(definition.canonicalDisplayName(), definition.brandSlug()));
                inserted++;
            } else if (!definition.canonicalDisplayName().equals(existing.get().getName())) {
                existing.get().updateName(definition.canonicalDisplayName());
                updated++; // JPA dirty checking persists the managed row in this transaction.
            } else {
                unchanged++;
            }
        }
        return new ScentRevCuratedBrandDbImportResult(selected.size(), inserted, updated, unchanged);
    }

    private List<CuratedBrandDefinition> loadValidatedDefinitions() {
        // Use original definitions: catalog.verifiedBrands() intentionally deduplicates for snapshots,
        // whereas this seed must reject duplicate eligible slugs before any persistence begins.
        var selected = configuration.load().parents().stream()
                .filter(CuratedBrandDefinition::importable).toList();
        var slugs = new HashSet<String>();
        for (var definition : selected) {
            String slug = definition.brandSlug();
            String name = definition.canonicalDisplayName();
            if (slug == null || slug.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("Eligible curated brands require a verified slug and preferred display name.");
            }
            if (!slugs.add(slug)) {
                throw new IllegalArgumentException("Duplicate verified provider slug in eligible curated definitions: " + slug);
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalStateException("No VERIFIED enabled curated brands to seed.");
        }
        return selected;
    }
}
