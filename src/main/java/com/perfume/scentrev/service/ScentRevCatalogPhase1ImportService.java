package com.perfume.scentrev.service;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Brand-granularity orchestration only; fail fast inside each brand, record failures and continue. */
@Service
public class ScentRevCatalogPhase1ImportService {

    private static final Logger log = LoggerFactory.getLogger(ScentRevCatalogPhase1ImportService.class);
    private final ScentRevCatalogBrandDiscoveryService discoveryService;
    private final ScentRevBrandPhase1ImportService brandImporter;

    public ScentRevCatalogPhase1ImportService(ScentRevCatalogBrandDiscoveryService discoveryService,
                                            ScentRevBrandPhase1ImportService brandImporter) {
        this.discoveryService = discoveryService;
        this.brandImporter = brandImporter;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScentRevCatalogImportResult importCatalog() {
        var discovery = discoveryService.discoverBrands(); // Failure here aborts before any brand import.
        log.info("Catalog brands discovered: {} ({} raw, {} duplicates)", discovery.uniqueBrandCount(),
                discovery.rawBrandCount(), discovery.duplicateBrandCount());
        var successes = new ArrayList<ScentRevBrandImportResult>();
        var failures = new ArrayList<ScentRevCatalogBrandFailure>();
        int position = 0;
        for (var brand : discovery.brands()) {
            String slug = brand.brandSlug();
            position++;
            log.info("[{}/{}] Importing brand: {}", position, discovery.uniqueBrandCount(), slug);
            try {
                var result = brandImporter.importBrand(slug);
                if (result == null || result.discovery() == null || !slug.equals(result.discovery().brandSlug())
                        || result.successfullyProcessedCount() != result.discovery().uniqueFragranceCount()) {
                    var failure = new ScentRevCatalogBrandFailure(slug, position, discovery.uniqueBrandCount(),
                            "INVALID_BRAND_RESULT", "Brand importer returned an inconsistent result.", null, 0);
                    failures.add(failure);
                    log.warn("[{}/{}] Failed brand {}: {}", position, discovery.uniqueBrandCount(), slug, failure.message());
                    continue;
                }
                successes.add(result); // An empty brand is a successful result with zero processed fragrances.
                log.info("[{}/{}] Completed brand {}: {} fragrances", position, discovery.uniqueBrandCount(),
                        slug, result.successfullyProcessedCount());
            } catch (RuntimeException exception) {
                var failure = safeFailure(slug, position, discovery.uniqueBrandCount(), exception);
                failures.add(failure);
                log.warn("[{}/{}] Failed brand {} ({}): {}", position, discovery.uniqueBrandCount(),
                        slug, failure.errorCategory(), failure.message()); // Never log a throwable/raw message.
            }
        }
        var result = new ScentRevCatalogImportResult(discovery, successes, failures);
        log.info("Catalog completed: {} brands succeeded, {} failed, {} fragrances processed",
                result.brandsSucceeded(), result.brandsFailed(), result.totalFragrancesProcessed());
        return result;
    }

    private static ScentRevCatalogBrandFailure safeFailure(String slug, int position, int total, RuntimeException exception) {
        if (exception instanceof ScentRevBrandImportException safe
                && safe.getDiscovery() != null && slug.equals(safe.getDiscovery().brandSlug())) {
            String category = safe.getStage().name();
            int processed = safe.getSuccessfullyProcessedCount();
            String message = "Brand import failed during " + category + " after " + processed
                    + " successfully processed fragrances."
                    + (safe.getFailedOffset() == null ? "" : " Discovery offset: " + safe.getFailedOffset() + ".");
            return new ScentRevCatalogBrandFailure(slug, position, total, category, message, safe.getDiscovery(), processed);
        }
        return new ScentRevCatalogBrandFailure(slug, position, total, "BRAND_IMPORT",
                "Brand import failed; previously committed data remains. Progress unavailable for this failure.", null, 0);
    }
}
