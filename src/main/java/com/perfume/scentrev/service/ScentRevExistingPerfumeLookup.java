package com.perfume.scentrev.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.perfume.repository.PerfumeImportIdentityProjection;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One read-only scalar query per discovered page. Uncertain identities retain the normal profile/conflict path. */
@Service
public class ScentRevExistingPerfumeLookup {
    private final PerfumeRepository perfumes;
    public ScentRevExistingPerfumeLookup(PerfumeRepository perfumes) { this.perfumes = perfumes; }

    public record Matches(Map<String, String> existingPublicIdsBySlug, Set<String> newCandidateSlugs) {
        public Matches { existingPublicIdsBySlug = Map.copyOf(existingPublicIdsBySlug); newCandidateSlugs = Set.copyOf(newCandidateSlugs); }
        static Matches empty() { return new Matches(Map.of(), Set.of()); }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Matches find(ScentRevSingleBrandImportService.ValidatedBrand brand, List<Fragrance> candidates) {
        if (candidates.isEmpty()) { return Matches.empty(); }
        var slugs = candidates.stream().map(Fragrance::fragranceSlug).distinct().toList();
        var ids = candidates.stream().map(Fragrance::publicId).filter(java.util.Objects::nonNull).distinct().toList();
        var rows = perfumes.findImportIdentities(slugs, ids);
        var existing = new HashMap<String, String>();
        var fresh = new HashSet<String>();
        for (var candidate : candidates) {
            var bySlug = rows.stream().filter(row -> candidate.fragranceSlug().equals(row.getFragranceSlug())).toList();
            var byId = candidate.publicId() == null ? List.<PerfumeImportIdentityProjection>of()
                    : rows.stream().filter(row -> candidate.publicId().equals(row.getPublicId())).toList();
            if (bySlug.isEmpty() && byId.isEmpty()) { fresh.add(candidate.fragranceSlug()); continue; }
            if (bySlug.size() != 1) { continue; }
            var stored = bySlug.get(0);
            if (stored.getId() == null || !brand.brandId().equals(stored.getBrandId()) || !brand.brandSlug().equals(stored.getBrandSlug())
                    || (candidate.brandSlug() != null && !brand.brandSlug().equals(candidate.brandSlug()))
                    || stored.getPublicId() == null || stored.getPublicId().isBlank() || stored.getPublicId().length() > 128
                    || !stored.getPublicId().equals(stored.getPublicId().strip())) { continue; }
            if (candidate.publicId() != null && (byId.size() != 1 || !stored.getId().equals(byId.get(0).getId())
                    || !candidate.publicId().equals(stored.getPublicId()))) { continue; }
            existing.put(candidate.fragranceSlug(), stored.getPublicId());
        }
        return new Matches(existing, fresh);
    }
}
