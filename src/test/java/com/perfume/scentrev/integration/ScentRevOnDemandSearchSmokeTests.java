package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.config.ScentRevClientProperties;

/** One explicitly gated read-only search; no Spring/JPA context, profile calls, retries or next page. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.ondemand-search-live-test", matches = "true")
class ScentRevOnDemandSearchSmokeTests {
    @Test
    void returnsOneSmallAventusCandidatePageWithoutImportingAnything() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        try (var client = new ScentRevMcpClient(properties, new ObjectMapper())) {
            var result = client.searchFragrances("Aventus", 5); // Exactly one search tool call.
            assertThat(result.results()).hasSizeLessThanOrEqualTo(5);
            System.out.printf("ScentRev on-demand search summary:%nquery: Aventus%nrequested limit: 5%nreturned candidates: %d%n",
                    result.results().size());
            result.results().stream().limit(5).forEach(candidate ->
                    System.out.printf("- %s: %s (%s)%n", candidate.fragranceSlug(), candidate.name(), candidate.brandName()));
        }
    }
}
