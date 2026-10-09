package com.perfume.scentrev.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.repository.BrandRepository;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.curated.CuratedBrandDefinition;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** One curated, seeded brand: sequential network calls outside transactions, then one committed page. */
@Service
public class ScentRevSingleBrandImportService {
    public static final int MAX_DISCOVERY_PAGES = 100;
    public static final int MAX_UNIQUE_FRAGRANCES = 1000;
    private final BrandRepository brands;
    private final ScentRevCuratedBrandConfiguration configuration;
    private final ScentRevMcpClient client;
    private final ScentRevPhase1ImportService persistence;

    public ScentRevSingleBrandImportService(BrandRepository brands, ScentRevCuratedBrandConfiguration configuration,
            ScentRevMcpClient client, ScentRevPhase1ImportService persistence) {
        this.brands = brands;
        this.configuration = configuration;
        this.client = client;
        this.persistence = persistence;
    }

    /** Local preflight only: require a verified curated identity and seeded Brand before discovery. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ValidatedBrand validateBrand(String brandSlug) {
        var approved = configuration.load().parents().stream()
                .filter(CuratedBrandDefinition::importable)
                .filter(parent -> brandSlug.equals(parent.brandSlug())).toList();
        if (approved.size() != 1) {
            throw new IllegalArgumentException("The requested slug must match exactly one VERIFIED enabled curated definition.");
        }
        var definition = approved.get(0);
        if (definition.resolutionQuery() == null || definition.resolutionQuery().isBlank()) {
            throw new IllegalArgumentException("The curated provider canonical/query identity is missing.");
        }
        var brand = brands.findByBrandSlug(brandSlug)
                .orElseThrow(() -> new IllegalArgumentException("The requested verified Brand is not seeded locally."));
        if (brand.getId() == null || !brandSlug.equals(brand.getBrandSlug())) {
            throw new IllegalArgumentException("The selected Brand has no persisted primary key or has a different slug.");
        }
        return new ValidatedBrand(brand.getId(), definition.canonicalDisplayName(), definition.resolutionQuery(), brandSlug);
    }

    public record ValidatedBrand(Long brandId, String brandName, String providerBrandName, String brandSlug) { }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScentRevSingleBrandImportResult importBrand(ScentRevSingleBrandImportOptions options) {
        var progress = new Progress(options.brandSlug(), options.delayMs());
        try {
            var brand = validateBrand(options.brandSlug());
            progress.brandName = brand.brandName();
            progress.providerName = brand.providerBrandName();
            System.err.printf("==================================================%nScentRev full single-brand import%n"
                            + "==================================================%nbrandName = %s%nproviderBrandName = %s%nbrandSlug = %s%n",
                    progress.brandName, progress.providerName, progress.brandSlug);
            while (true) {
                progress.stage = "DISCOVERY";
                progress.currentSlug = null;
                if (progress.pages >= MAX_DISCOVERY_PAGES) {
                    throw new IllegalArgumentException("Maximum discovery pages (100) reached while the provider still reports more results.");
                }
                var page = progress.request(() -> client.searchFragrancesFiltered(progress.brandSlug, progress.offset));
                progress.pages++;
                validatePage(page, progress);
                progress.rawResults += page.results().size();
                int before = progress.unique;
                var newFragrances = discoverUnique(page.results(), progress);
                System.err.printf("%nDISCOVERY PAGE %d%noffset = %d%nreturned = %d%nproviderPageReturned = %s%n"
                                + "uniqueTotal = %d%ntruncated = %s%n",
                        progress.pages, progress.offset, page.results().size(), page.totalReturned(), progress.unique, page.truncated());
                if (progress.unique > MAX_UNIQUE_FRAGRANCES) {
                    throw new IllegalArgumentException("Maximum unique fragrances (1000) exceeded.");
                }
                if (page.truncated() && before == progress.unique) {
                    throw new IllegalArgumentException("Repeated/non-progressing truncated page introduced no new fragrance identities.");
                }
                var profiles = new ArrayList<ScentRevFragranceProfileResponse>();
                int profilePosition = 0;
                int skippedBefore = progress.skippedForeignBrand;
                for (var fragrance : newFragrances) {
                    progress.stage = "PROFILE";
                    progress.currentSlug = fragrance.fragranceSlug();
                    System.err.printf("%nPROFILE [%d/%d]%nname = %s%nslug = %s%n", ++profilePosition,
                            newFragrances.size(), oneLine(fragrance.name()), fragrance.fragranceSlug());
                    var profile = progress.request(() -> client.getFragranceProfile(fragrance.fragranceSlug()));
                    progress.profiles++;
                    if (!validateDiscoveredProfile(fragrance, profile, progress)) {
                        progress.skippedForeignBrand++;
                        System.err.printf("%nSKIPPED FOREIGN BRAND RESULT%nrequestedBrandSlug = %s%n"
                                        + "fragranceSlug = %s%nactualBrandSlug = %s%n",
                                progress.brandSlug, fragrance.fragranceSlug(), profile.identity().brandSlug());
                        continue;
                    }
                    if (ScentRevProfileDataService.needsWearSummary(profile)) {
                        progress.stage = "WEAR_SUMMARY";
                        System.err.printf("WEAR SUMMARY%nslug = %s%n", fragrance.fragranceSlug());
                        var wear = progress.request(() -> client.getWearSummary(fragrance.fragranceSlug()));
                        ScentRevProfileDataService.validateWearSummary(profile, wear);
                        profile = profile.withWearSummary(wear);
                    }
                    profiles.add(profile);
                }
                if (profiles.isEmpty()) {
                    // Foreign-only/empty pages advance normally without creating an empty persistence transaction.
                    System.err.printf("%nPAGE %d COMPLETED WITHOUT PERSISTENCE%naccepted = 0%nskippedForeignBrand = %d%n",
                            progress.pages, progress.skippedForeignBrand - skippedBefore);
                } else {
                    progress.stage = "VALIDATION";
                    persistence.validateProfilePageForBrand(progress.brandSlug, profiles);
                    progress.stage = "PERSISTENCE";
                    progress.currentSlug = null;
                    var committed = persistence.importPageForSeededBrand(brand.brandId(), progress.brandSlug, profiles);
                    progress.committedPages++;
                    progress.inserted += committed.perfumesInserted();
                    progress.updated += committed.perfumesUpdated();
                    progress.unchanged += committed.perfumesUnchanged();
                    progress.perfumerIds.addAll(committed.perfumerIds());
                    progress.noteNames.addAll(committed.noteNames());
                    progress.accordNames.addAll(committed.accordNames());
                    progress.perfumerLinks += committed.perfumePerfumerLinks();
                    progress.noteLinks += committed.perfumeNoteLinks();
                    progress.accordLinks += committed.perfumeAccordLinks();
                    System.err.printf("%nPAGE %d COMMITTED%ninserted = %d%nupdated = %d%nunchanged = %d%nprocessed = %d%n"
                                    + "skippedForeignBrand = %d%n",
                            progress.pages, committed.perfumesInserted(), committed.perfumesUpdated(),
                            committed.perfumesUnchanged(), committed.perfumesProcessed(), progress.skippedForeignBrand - skippedBefore);
                }
                if (!page.truncated()) { return progress.snapshot(true); }
                // Connected MCP metadata explicitly specifies offset + limit (not the number of rows).
                progress.offset = Math.addExact(page.offset(), page.limit());
            }
        } catch (RuntimeException exception) {
            String diagnostic;
            if (exception instanceof ScentRevMcpClientException safe) {
                diagnostic = "ScentRev request failed (" + safe.getFailureType()
                        + (safe.getStatusCode() == null ? "" : ", HTTP " + safe.getStatusCode())
                        + "); no retry or subsequent request was made.";
            } else if (exception instanceof ScentRevPhase1ImportService.IdentityConflictException
                    || (exception instanceof IllegalArgumentException && !"PERSISTENCE".equals(progress.stage))) {
                diagnostic = exception.getMessage(); // Only local validation diagnostics; never raw SDK/persistence payloads.
            } else {
                diagnostic = "The " + progress.stage + " operation failed; review configuration/provider/database availability. No automatic retry was made.";
            }
            throw new ScentRevSingleBrandImportException(progress.snapshot(false), diagnostic);
        }
    }

    private static void validatePage(ScentRevFilteredSearchResponse page, Progress progress) {
        if (page == null || page.results() == null || page.truncated() == null || page.offset() == null || page.limit() == null) {
            throw new IllegalArgumentException("Missing discovery results or pagination metadata.");
        }
        if (page.offset() != progress.offset || page.limit() != ScentRevMcpClient.SEARCH_PAGE_LIMIT
                || page.results().size() > page.limit() || Boolean.TRUE.equals(page.partial())
                || (page.totalReturned() != null && page.totalReturned() != page.results().size())) {
            throw new IllegalArgumentException("Incomplete/inconsistent discovery page or unexpected offset/limit.");
        }
    }

    private static List<Fragrance> discoverUnique(List<Fragrance> fragrances, Progress progress) {
        var fresh = new ArrayList<Fragrance>();
        for (var fragrance : fragrances) {
            if (fragrance == null || !canonicalSlug(fragrance.fragranceSlug())) {
                throw new IllegalArgumentException("Discovery returned a missing or malformed fragrance slug.");
            }
            // Search filtering can include foreign brands; only the fetched profile confirms its actual brand.
            String slug = fragrance.fragranceSlug();
            String id = fragrance.publicId();
            if (id != null && (id.isBlank() || id.length() > 128 || !id.equals(id.strip()))) {
                throw new IllegalArgumentException("Discovery returned an invalid public ID.");
            }
            String knownId = progress.publicIdsBySlug.get(slug);
            if (knownId != null && id != null && !knownId.equals(id)) {
                throw new IllegalArgumentException("One fragrance slug has conflicting public IDs.");
            }
            boolean duplicate = progress.seenSlugs.contains(slug) || (id != null && progress.seenPublicIds.contains(id));
            progress.seenSlugs.add(slug);
            if (id != null) {
                progress.seenPublicIds.add(id);
                progress.publicIdsBySlug.put(slug, id);
            }
            if (duplicate) { progress.duplicates++; }
            else { progress.unique++; fresh.add(fragrance); }
        }
        return fresh;
    }

    /** Preserve fragrance/public-ID integrity first; false means a confirmed foreign brand that may be skipped. */
    private static boolean validateDiscoveredProfile(Fragrance fragrance, ScentRevFragranceProfileResponse profile, Progress progress) {
        if (profile == null || profile.identity() == null) { throw new IllegalArgumentException("The profile has no identity section."); }
        var identity = profile.identity();
        if (!fragrance.fragranceSlug().equals(identity.fragranceSlug())
                || identity.publicId() == null || identity.publicId().isBlank() || identity.publicId().length() > 128
                || !identity.publicId().equals(identity.publicId().strip())
                || (fragrance.publicId() != null && !fragrance.publicId().equals(identity.publicId()))) {
            throw new IllegalArgumentException("Profile fragrance/public identity does not match discovery.");
        }
        if (!canonicalSlug(identity.brandSlug())) {
            throw new IllegalArgumentException("Profile brand identity is missing or malformed; a foreign brand cannot be confirmed.");
        }
        String knownId = progress.publicIdsBySlug.get(fragrance.fragranceSlug());
        if (knownId != null && !knownId.equals(identity.publicId())) {
            throw new IllegalArgumentException("Profile public ID conflicts with another occurrence of the discovered slug.");
        }
        if (fragrance.publicId() == null && !progress.seenPublicIds.add(identity.publicId())
                && !identity.publicId().equals(knownId)) {
            throw new IllegalArgumentException("Profile resolved a conflicting public ID already associated with another discovery result.");
        }
        progress.publicIdsBySlug.put(fragrance.fragranceSlug(), identity.publicId());
        return progress.brandSlug.equals(identity.brandSlug());
    }

    private static boolean canonicalSlug(String slug) {
        return slug != null && slug.length() <= 255 && slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*");
    }
    private static String oneLine(String text) { return text == null ? "(not supplied)" : text.replace('\n', ' ').replace('\r', ' '); }

    private static class Progress {
        private final String brandSlug;
        private final long delayMs;
        private String brandName, providerName, currentSlug;
        private String stage = "BRAND_VALIDATION";
        private int offset, pages, committedPages, rawResults, duplicates, unique, profiles, inserted, updated, unchanged;
        private int perfumerLinks, noteLinks, accordLinks, calls, skippedForeignBrand;
        private Long lastRequestFinished;
        private final Set<String> seenSlugs = new HashSet<>();
        private final Set<String> seenPublicIds = new HashSet<>();
        private final Map<String, String> publicIdsBySlug = new HashMap<>();
        private final Set<String> perfumerIds = new HashSet<>(), noteNames = new HashSet<>(), accordNames = new HashSet<>();
        private Progress(String brandSlug, long delayMs) { this.brandSlug = brandSlug; this.delayMs = delayMs; }
        private <T> T request(Supplier<T> call) {
            if (lastRequestFinished != null) {
                long remaining = TimeUnit.MILLISECONDS.toNanos(delayMs) - (System.nanoTime() - lastRequestFinished);
                if (remaining > 0) {
                    try { TimeUnit.NANOSECONDS.sleep(remaining); }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Import interrupted during the configured request delay.");
                    }
                }
            }
            calls++;
            try { return call.get(); }
            finally { lastRequestFinished = System.nanoTime(); }
        }
        private ScentRevSingleBrandImportResult snapshot(boolean completed) {
            // The inspected filtered-search contract has total_returned per page, no overall catalog total.
            return new ScentRevSingleBrandImportResult(brandName, providerName, brandSlug, pages, committedPages,
                    rawResults, duplicates, unique, null, profiles, skippedForeignBrand, inserted, updated, unchanged,
                    perfumerIds.size(), noteNames.size(), accordNames.size(), perfumerLinks, noteLinks, accordLinks,
                    calls, completed, completed ? null : stage, completed ? null : offset, completed ? null : currentSlug);
        }
    }
}
