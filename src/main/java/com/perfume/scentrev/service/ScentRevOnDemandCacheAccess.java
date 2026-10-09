package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevOnDemandException.FailureType.IDENTIFIER_CONFLICT;
import static com.perfume.scentrev.service.ScentRevOnDemandException.failure;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.perfume.domain.Perfume;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** Database-only boundaries. The one selected perfume and its identity checks share one atomic transaction. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
public class ScentRevOnDemandCacheAccess {
    private final PerfumeRepository perfumes;
    private final ScentRevPhase1ImportService importer;
    public ScentRevOnDemandCacheAccess(PerfumeRepository perfumes, ScentRevPhase1ImportService importer) {
        this.perfumes = perfumes;
        this.importer = importer;
    }

    public List<PerfumeSearchCandidate> searchCached(String query, int limit) {
        if (limit < 1 || limit > 10) { throw failure(ScentRevOnDemandException.FailureType.INVALID_LIMIT); }
        String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return perfumes.searchCached(pattern, PageRequest.of(0, limit)).stream().limit(limit)
                .map(row -> new PerfumeSearchCandidate(row.getFragranceSlug(), row.getPublicId(), row.getName(),
                        row.getBrandName(), row.getBrandSlug(), true, PerfumeSearchCandidate.Source.LOCAL)).toList();
    }

    public Optional<Perfume> findCached(String slug) {
        var cached = perfumes.findByFragranceSlug(slug);
        // The public API returns Perfume; initialize its only parent while this read transaction is open.
        cached.ifPresent(perfume -> perfume.getBrand().getName());
        return cached;
    }

    public Set<String> cachedSlugs(Collection<String> slugs) {
        if (slugs.size() > 10) { throw failure(ScentRevOnDemandException.FailureType.INVALID_LIMIT); }
        return slugs.isEmpty() ? Set.of() : Set.copyOf(perfumes.findCachedFragranceSlugs(slugs));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void importSelected(String slug, ScentRevFragranceProfileResponse profile) {
        var identity = profile.identity();
        perfumes.findByScentrevPublicId(identity.publicId()).ifPresent(existing -> {
            if (!slug.equals(existing.getFragranceSlug())) { throw failure(IDENTIFIER_CONFLICT); }
        });
        Perfume imported = importer.importProfile(profile); // Existing REQUIRED mapper joins this one-perfume transaction.
        if (!matches(imported, slug, profile)) {
            // Covers a public-ID race after the first lookup, rolling back any mapper associations as well.
            throw failure(IDENTIFIER_CONFLICT);
        }
    }

    static boolean matches(Perfume perfume, String slug, ScentRevFragranceProfileResponse profile) {
        return perfume != null && perfume.getId() != null && slug.equals(perfume.getFragranceSlug())
                && profile.identity().publicId().equals(perfume.getScentrevPublicId()) && perfume.getBrand() != null
                && profile.identity().brandSlug().equals(perfume.getBrand().getBrandSlug());
    }
}
