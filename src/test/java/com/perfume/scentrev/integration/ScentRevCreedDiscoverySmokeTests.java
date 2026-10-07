package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.service.ScentRevBrandDiscoveryService;

/** Opt-in, Creed-only search. No Spring context, profile calls, JDBC or persistence. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.creed-discovery-live-test", matches = "true")
class ScentRevCreedDiscoverySmokeTests {

    @Test
    void discoversCompleteCurrentCreedCatalogWithoutPersistence() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        try (var client = new ScentRevMcpClient(properties, new ObjectMapper())) {
            var result = new ScentRevBrandDiscoveryService(client).discoverBrandFragrances("creed");
            assertThat(result.brandSlug()).isEqualTo("creed");
            assertThat(result.uniqueFragranceCount()).isPositive();
            assertThat(result.fragrances()).extracting(Fragrance::fragranceSlug).doesNotHaveDuplicates();
            assertThat(result.discoveredResultCount()).isEqualTo(result.uniqueFragranceCount() + result.duplicateDiscoveryCount());
            System.out.printf("Creed discovery summary:%nraw results: %d%nunique fragrances: %d%nduplicates: %d%n",
                    result.discoveredResultCount(), result.uniqueFragranceCount(), result.duplicateDiscoveryCount());
        }
    }
}
