package com.perfume.scentrev.curated;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;

/** One queried page per attempt; reports candidates only, never approves or persists a slug. */
@Service
public class ScentRevCuratedBrandResolver {
    public static final int DEFAULT_CHUNK_SIZE = 5;
    public static final int MAX_CHUNK_SIZE = 10;
    private final ScentRevMcpClient client;
    public ScentRevCuratedBrandResolver(ScentRevMcpClient client) { this.client = client; }

    public Resolution resolve(CuratedBrandDefinition parent) {
        if (!parent.enabled() || parent.verificationStatus() != CuratedBrandDefinition.VerificationStatus.UNRESOLVED) {
            throw new IllegalArgumentException("Resolution requires an enabled unresolved curated parent.");
        }
        if (parent.resolutionQuery() == null || parent.resolutionQuery().isBlank()) {
            return new Resolution(parent, Status.UNRESOLVED, List.of(), "Canonical query needs explicit human review; no request made.");
        }
        var response = client.searchBrands(parent.resolutionQuery(), MAX_CHUNK_SIZE);
        if (response == null || response.brands() == null || response.brands().size() > MAX_CHUNK_SIZE
                || response.offset() != null && response.offset() != 0
                || response.limit() != null && (response.limit() < 1 || response.limit() > MAX_CHUNK_SIZE)
                || response.totalReturned() != null && response.totalReturned() != response.brands().size()) {
            throw new IllegalStateException("Invalid targeted brand response; stop resolution and inspect the contract.");
        }
        var unique = new LinkedHashMap<String, CatalogBrand>();
        for (var brand : response.brands()) {
            if (brand == null || brand.brandSlug() == null || brand.brandSlug().length() > 255
                    || !brand.brandSlug().matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || brand.brandName() == null || brand.brandName().isBlank()
                    || brand.fragranceCount() != null && brand.fragranceCount() < 0) {
                throw new IllegalStateException("Invalid targeted brand candidate; no mapping changed.");
            }
            var previous = unique.putIfAbsent(brand.brandSlug(), brand);
            if (previous != null && !previous.equals(brand)) {
                throw new IllegalStateException("Conflicting canonical brand candidates; manual review required.");
            }
        }
        var candidates = List.copyOf(unique.values());
        // A truncated page is never proof of uniqueness. Deliberately ignore any next cursor.
        if (Boolean.TRUE.equals(response.truncated()) || candidates.size() > 1
                || !candidates.isEmpty() && response.truncated() == null) {
            return new Resolution(parent, Status.AMBIGUOUS, candidates, "Multiple or truncated matches; review manually, no pagination.");
        }
        if (candidates.size() == 1 && normalize(candidates.get(0).brandName()).equals(normalize(parent.resolutionQuery()))) {
            return new Resolution(parent, Status.CANDIDATE, candidates, "Exact name candidate; operator approval still required.");
        }
        return new Resolution(parent, Status.UNRESOLVED, candidates, "No exact canonical name match; review query/name manually.");
    }

    /** Stable normalized-parent order; no loop over entries outside this bounded slice. */
    public List<CuratedBrandDefinition> chunk(CuratedBrandCatalog catalog, int start, int size) {
        if (start < 0 || size < 1 || size > MAX_CHUNK_SIZE) {
            throw new IllegalArgumentException("Resolution start must be nonnegative and size between 1 and 10.");
        }
        var unresolved = catalog.unresolvedParents();
        if (start >= unresolved.size()) { return List.of(); }
        return unresolved.subList(start, start + Math.min(size, unresolved.size() - start));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    public enum Status { CANDIDATE, AMBIGUOUS, UNRESOLVED }
    public record Resolution(CuratedBrandDefinition parent, Status status, List<CatalogBrand> candidates, String reason) {
        public Resolution { candidates = List.copyOf(candidates); }
    }
}
