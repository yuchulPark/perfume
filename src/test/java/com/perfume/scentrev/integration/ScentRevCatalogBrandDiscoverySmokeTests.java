package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;
import com.perfume.scentrev.service.ScentRevCatalogBrandDiscoveryResult;
import com.perfume.scentrev.service.ScentRevCatalogBrandDiscoveryService;

/** Opt-in list_brands only: no Spring context, fragrance requests, JDBC or persistence. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.catalog-discovery-live-test", matches = "true")
class ScentRevCatalogBrandDiscoverySmokeTests {

    @Test
    void discoversCompleteCurrentBrandCatalogWithoutFragranceRequestsOrPersistence() {
        var properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        try (var client = new ScentRevMcpClient(properties, new ObjectMapper())) {
            var result = new ScentRevCatalogBrandDiscoveryService(client).discoverBrands();
            assertThat(result.uniqueBrandCount()).isPositive();
            assertThat(result.brands()).allSatisfy(brand -> assertThat(brand.brandSlug()).isNotBlank());
            assertThat(result.brands()).extracting(CatalogBrand::brandSlug).doesNotHaveDuplicates();
            assertThat(result.rawBrandCount()).isEqualTo(result.uniqueBrandCount() + result.duplicateBrandCount());
            var slugs = result.brands().stream().map(CatalogBrand::brandSlug).toList();
            System.out.println("ScentRev brand discovery summary:");
            System.out.println("raw brands: " + result.rawBrandCount());
            System.out.println("unique brands: " + result.uniqueBrandCount());
            System.out.println("duplicates: " + result.duplicateBrandCount());
            System.out.println("pages: " + result.pageCount());
            System.out.println("first brand slugs: " + slugs.subList(0, Math.min(5, slugs.size())));
            System.out.println("last brand slugs: " + slugs.subList(Math.max(0, slugs.size() - 5), slugs.size()));
            printCatalogSizeSummary(result);
            System.out.flush();
        }
    }

    private static void printCatalogSizeSummary(ScentRevCatalogBrandDiscoveryResult result) {
        var knownCounts = result.brands().stream().filter(brand -> brand.fragranceCount() != null).toList();
        long total = knownCounts.stream().mapToLong(CatalogBrand::fragranceCount).sum();
        long withFragrances = knownCounts.stream().filter(brand -> brand.fragranceCount() > 0).count();
        long zeroFragrances = knownCounts.stream().filter(brand -> brand.fragranceCount() == 0).count();
        int unknownCounts = result.brands().size() - knownCounts.size();
        var topBrands = knownCounts.stream()
                .sorted(Comparator.comparingLong((CatalogBrand brand) -> brand.fragranceCount()).reversed()
                        .thenComparing(CatalogBrand::brandSlug))
                .limit(10).toList();

        System.out.println("ScentRev catalog size summary:");
        System.out.println("total reported fragrances: " + total + " (sum of known counts)");
        System.out.println("brands with fragrances: " + withFragrances);
        System.out.println("brands with zero fragrances: " + zeroFragrances);
        System.out.println("brands with unknown fragrance counts: " + unknownCounts);
        System.out.println("maximum fragrance_count: " + (topBrands.isEmpty() ? "unknown" : topBrands.get(0).fragranceCount()));
        System.out.println("largest brand: " + (topBrands.isEmpty() ? "unknown (no reported counts)"
                : displayBrand(topBrands.get(0)) + " : " + topBrands.get(0).fragranceCount()));
        System.out.println("top 10 brands:");
        if (topBrands.isEmpty()) {
            System.out.println("(none with reported counts)");
        }
        for (int index = 0; index < topBrands.size(); index++) {
            var brand = topBrands.get(index);
            System.out.println((index + 1) + ". " + displayBrand(brand) + " : " + brand.fragranceCount());
        }
    }

    private static String displayBrand(CatalogBrand brand) {
        String name = brand.brandName() == null || brand.brandName().isBlank() ? "name unknown" : brand.brandName();
        return brand.brandSlug() + " (" + name + ")";
    }
}
