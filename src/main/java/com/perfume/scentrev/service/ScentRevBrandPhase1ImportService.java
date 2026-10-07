package com.perfume.scentrev.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.service.ScentRevBrandImportException.Stage;

/** One brand, sequential profiles, fail fast, and one existing mapper transaction per fragrance. */
@Service
public class ScentRevBrandPhase1ImportService {

    private final ScentRevBrandDiscoveryService discoveryService;
    private final ScentRevMcpClient client;
    private final ScentRevPhase1ImportService importer;

    public ScentRevBrandPhase1ImportService(ScentRevBrandDiscoveryService discoveryService,
                                          ScentRevMcpClient client, ScentRevPhase1ImportService importer) {
        this.discoveryService = discoveryService;
        this.client = client;
        this.importer = importer;
    }

    /** Suspend even an ambient transaction so prior mapper commits survive later failures. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScentRevBrandImportResult importBrand(String brandSlug) {
        ScentRevBrandDiscoveryResult discovery = discoveryService.discoverBrandFragrances(brandSlug);
        int processed = 0;
        for (var fragrance : discovery.fragrances()) {
            String slug = fragrance.fragranceSlug();
            ScentRevFragranceProfileResponse profile;
            try {
                profile = client.getFragranceProfile(slug);
            } catch (RuntimeException exception) {
                String category = exception instanceof ScentRevMcpClientException safe
                        ? " (" + safe.getFailureType() + ")" : "";
                throw ScentRevBrandImportException.importing(Stage.PROFILE, discovery, processed, slug,
                        "MCP profile request failed" + category + ".");
            }
            if (profile == null || profile.identity() == null
                    || !brandSlug.equals(profile.identity().brandSlug())
                    || !slug.equals(profile.identity().fragranceSlug())) {
                throw ScentRevBrandImportException.importing(Stage.IDENTITY, discovery, processed, slug,
                        "Profile brand/fragrance identity does not match the requested canonical identity.");
            }
            if (fragrance.publicId() != null && !fragrance.publicId().equals(profile.identity().publicId())) {
                throw ScentRevBrandImportException.importing(Stage.IDENTITY, discovery, processed, slug,
                        "Profile public ID does not match discovery.");
            }
            try {
                importer.importProfile(profile);
            } catch (RuntimeException exception) {
                throw ScentRevBrandImportException.importing(Stage.PERSISTENCE, discovery, processed, slug,
                        "Phase 1 persistence failed; earlier successful imports remain committed.");
            }
            processed++; // The proxied mapper has returned, including its transaction commit.
        }
        return new ScentRevBrandImportResult(discovery, processed);
    }
}
