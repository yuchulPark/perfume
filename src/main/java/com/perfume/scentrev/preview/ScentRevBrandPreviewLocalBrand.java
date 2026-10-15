package com.perfume.scentrev.preview;

import com.perfume.repository.BrandRepository;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.curated.CuratedBrandDefinition;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Local reads only; no dependency on any Import/persistence service. */
@Component
@ConditionalOnProperty(name = ScentRevBrandPreviewOptions.PREFIX, havingValue = "true")
public class ScentRevBrandPreviewLocalBrand {
    private final BrandRepository brands;
    private final PerfumeRepository perfumes;
    private final ScentRevCuratedBrandConfiguration configuration;

    public ScentRevBrandPreviewLocalBrand(BrandRepository brands, PerfumeRepository perfumes,
            ScentRevCuratedBrandConfiguration configuration) {
        this.brands = brands; this.perfumes = perfumes; this.configuration = configuration;
    }

    public record Snapshot(String brandName, String brandSlug, long dbPerfumeCount) { }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Snapshot read(String slug) {
        var approved = configuration.load().parents().stream().filter(CuratedBrandDefinition::importable)
                .filter(parent -> slug.equals(parent.brandSlug())).toList();
        if (approved.size() != 1 || approved.get(0).resolutionQuery() == null || approved.get(0).resolutionQuery().isBlank()) {
            throw new IllegalArgumentException("Preview requires exactly one VERIFIED enabled curated definition with a provider query.");
        }
        var brand = brands.findByBrandSlug(slug)
                .orElseThrow(() -> new IllegalArgumentException("The verified preview Brand is not seeded locally."));
        if (brand.getId() == null || !slug.equals(brand.getBrandSlug())) {
            throw new IllegalArgumentException("The preview Brand must have a persisted ID and matching slug.");
        }
        return new Snapshot(approved.get(0).canonicalDisplayName(), slug, perfumes.countByBrand_Id(brand.getId()));
    }
}
