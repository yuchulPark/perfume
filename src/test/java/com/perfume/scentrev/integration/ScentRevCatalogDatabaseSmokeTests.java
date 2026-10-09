package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.perfume.scentrev.service.ScentRevCatalogImportResult;
import com.perfume.scentrev.service.ScentRevCatalogPhase1ImportService;

/**
 * Explicitly gated full catalog import; potentially many MCP calls. One pass only.
 * Independent successful commits remain, even when the final test reports brand failures.
 * Schema validation only; no cleanup, schema recreation, second import or test transaction.
 */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.catalog-db-live-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCatalogDatabaseSmokeTests {

    @Autowired
    private ScentRevCatalogPhase1ImportService importer;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void importsFullPhase1CatalogOnceAndReportsFailuresBeforeFailingTheTest() {
        var priorCreed = storedPerfumes().stream().filter(perfume -> "creed".equals(perfume.brandSlug())).toList();
        var priorCreedLinks = creedAssociationIds();

        var result = importer.importCatalog(); // One complete run, never automatically repeated.
        printSummary(result); // Report every recorded failure before any final assertions can fail.
        assertThat(result.uniqueBrandCount()).isPositive();
        assertThat(result.brandsAttempted()).isEqualTo(result.uniqueBrandCount());

        assertNoDuplicateIdentifiersOrAssociations();
        assertThat(count("""
                select count(*) from perfumes p left join brands b on b.id = p.brand_id where b.id is null
                """)).as("Every persisted perfume references a valid Brand").isZero();

        var stored = storedPerfumes();
        Map<String, StoredPerfume> bySlug = stored.stream().collect(Collectors.toMap(StoredPerfume::slug, perfume -> perfume));
        Map<String, Long> brandCounts = jdbc.query("select brand_slug, count(*) as row_count from brands group by brand_slug",
                (row, index) -> Map.entry(row.getString("brand_slug"), row.getLong("row_count")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (var brand : result.successfulBrands()) {
            String slug = brand.discovery().brandSlug();
            assertThat(brandCounts.getOrDefault(slug, 0L)).as("At most one Brand row for %s", slug).isLessThanOrEqualTo(1L);
            if (brand.discovery().uniqueFragranceCount() > 0) {
                assertThat(brandCounts.getOrDefault(slug, 0L)).isEqualTo(1L);
            }
            for (var fragrance : brand.discovery().fragrances()) {
                var perfume = bySlug.get(fragrance.fragranceSlug());
                assertThat(perfume).as("Persisted fragrance %s", fragrance.fragranceSlug()).isNotNull();
                assertThat(perfume.brandSlug()).as("Brand for %s", fragrance.fragranceSlug()).isEqualTo(slug);
                if (fragrance.publicId() != null) {
                    assertThat(perfume.publicId()).isEqualTo(fragrance.publicId());
                }
            }
        }

        assertThat(stored).as("All previously committed Creed rows remain unchanged").containsAll(priorCreed);
        var afterCreedLinks = creedAssociationIds();
        priorCreedLinks.forEach((table, ids) -> assertThat(afterCreedLinks.get(table))
                .as("Previously committed Creed %s associations remain", table).containsAll(ids));
        assertThat(bySlug.get("creed-aventus")).as("Previously verified Aventus remains present")
                .isNotNull().satisfies(perfume -> assertThat(perfume.brandSlug()).isEqualTo("creed"));

        var creed = result.successfulBrands().stream().filter(brand -> "creed".equals(brand.discovery().brandSlug())).toList();
        if (!creed.isEmpty()) {
            assertThat(creed).hasSize(1);
            var sameRunSlugs = creed.get(0).discovery().fragrances().stream().map(fragrance -> fragrance.fragranceSlug()).toList();
            assertThat(sameRunSlugs).isNotEmpty();
            assertThat(stored.stream().filter(perfume -> "creed".equals(perfume.brandSlug())).map(StoredPerfume::slug).toList())
                    .as("Same-run Creed discovery is present; older additive rows may also remain")
                    .containsAll(sameRunSlugs);
        }
        // All successful data stays committed regardless of this final completeness assertion.
        assertThat(result.brandsFailed()).as("Catalog gaps were printed above; successful imports remain committed").isZero();
        assertThat(creed).as("The complete current brand catalog includes successfully processed Creed").hasSize(1);
    }

    private static void printSummary(ScentRevCatalogImportResult result) {
        System.out.printf("ScentRev Phase 1 catalog import summary:%nraw brands: %d%nbrands discovered: %d%n"
                        + "duplicate brands: %d%nbrand pages: %d%nbrands attempted: %d%nbrands succeeded: %d%n"
                        + "brands failed: %d%nfragrances discovered: %d%nunique fragrances: %d%nfragrances processed: %d%n",
                result.rawBrandCount(), result.uniqueBrandCount(), result.duplicateBrandCount(), result.discovery().pageCount(),
                result.brandsAttempted(), result.brandsSucceeded(), result.brandsFailed(), result.totalFragrancesDiscovered(),
                result.totalUniqueFragrances(), result.totalFragrancesProcessed());
        if (!result.failedBrands().isEmpty()) {
            System.out.println("Failed brands:");
            for (var failure : result.failedBrands()) {
                System.out.printf("- [%d/%d] %s (%s): %s%n", failure.brandPosition(), failure.totalBrands(),
                        failure.brandSlug(), failure.errorCategory(), failure.message());
            }
        }
    }

    private List<StoredPerfume> storedPerfumes() {
        return jdbc.query("""
                select p.id, p.fragrance_slug, p.scentrev_public_id, b.brand_slug
                from perfumes p left join brands b on b.id = p.brand_id order by p.id
                """, (row, index) -> new StoredPerfume(row.getLong("id"), row.getString("fragrance_slug"),
                        row.getString("scentrev_public_id"), row.getString("brand_slug")));
    }

    private Map<String, Set<Long>> creedAssociationIds() {
        var links = new HashMap<String, Set<Long>>();
        for (String table : List.of("perfume_perfumers", "perfume_notes", "perfume_accords")) {
            // Table names are fixed test-owned constants; no provider input is interpolated.
            var ids = jdbc.queryForList("select link.id from " + table
                    + " link join perfumes p on p.id = link.perfume_id join brands b on b.id = p.brand_id where b.brand_slug = ?",
                    Long.class, "creed");
            links.put(table, Set.copyOf(ids));
        }
        return links;
    }

    private void assertNoDuplicateIdentifiersOrAssociations() {
        assertThat(count("select count(*) from (select fragrance_slug from perfumes group by fragrance_slug having count(*) > 1) duplicates"))
                .as("Global fragrance_slug uniqueness").isZero();
        assertThat(count("""
                select count(*) from (
                    select scentrev_public_id from perfumes where scentrev_public_id is not null
                    group by scentrev_public_id having count(*) > 1
                ) duplicates
                """)).as("Global non-null ScentRev public ID uniqueness").isZero();
        assertThat(count("""
                select count(*) from (
                    select perfume_id, perfumer_id from perfume_perfumers
                    group by perfume_id, perfumer_id having count(*) > 1
                ) duplicates
                """)).as("Perfume/perfumer association uniqueness").isZero();
        assertThat(count("""
                select count(*) from (
                    select perfume_id, note_id, layer from perfume_notes
                    group by perfume_id, note_id, layer having count(*) > 1
                ) duplicates
                """)).as("Perfume/note/layer association uniqueness").isZero();
        assertThat(count("""
                select count(*) from (
                    select perfume_id, accord_id from perfume_accords
                    group by perfume_id, accord_id having count(*) > 1
                ) duplicates
                """)).as("Perfume/accord association uniqueness").isZero();
    }

    private Long count(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private record StoredPerfume(long id, String slug, String publicId, String brandSlug) { }
}
