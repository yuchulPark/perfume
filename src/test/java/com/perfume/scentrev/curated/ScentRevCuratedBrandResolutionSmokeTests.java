package com.perfume.scentrev.curated;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.config.ScentRevClientProperties;

/** Manually gated, bounded candidate reporting. No Spring/DB context or source writes. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.curated-resolution-live-test", matches = "true")
class ScentRevCuratedBrandResolutionSmokeTests {
    @Test
    void reportsOneSmallUnresolvedParentChunkWithoutImportingOrApprovingAnything() {
        var mapper = new ObjectMapper();
        var catalog = new ScentRevCuratedBrandConfiguration(mapper).load();
        int start = Integer.parseInt(System.getProperty("scentrev.curated-resolution-start", "0"));
        int size = Integer.parseInt(System.getProperty("scentrev.curated-resolution-size", "5"));
        var properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        try (var client = new ScentRevMcpClient(properties, mapper)) {
            var resolver = new ScentRevCuratedBrandResolver(client);
            var chunk = resolver.chunk(catalog, start, size);
            System.out.printf("Curated resolution: unresolved-parent start %d, selected %d, hard maximum 10%n", start, chunk.size());
            for (int index = 0; index < chunk.size(); index++) {
                var parent = chunk.get(index);
                System.out.printf("[%d] %s [%s]%n", start + index + 1, parent.canonicalDisplayName(), parent.canonicalBrandKey());
                var result = resolver.resolve(parent); // Any error aborts this chunk. No automatic retry.
                System.out.printf("%s: %s%n", result.status(), result.reason());
                result.candidates().forEach(candidate -> System.out.printf("  brand_name = %s%n  brand_slug = %s%n  fragrance_count = %s%n",
                        candidate.brandName(), candidate.brandSlug(), candidate.fragranceCount() == null ? "unknown" : candidate.fragranceCount()));
            }
            System.out.println("Candidates require operator review; no mapping changed and no database accessed.");
        }
    }
}
