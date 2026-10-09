package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import com.perfume.scentrev.progress.CatalogImportBrandProgressRepository;
import com.perfume.scentrev.progress.CatalogImportBrandStatus;
import com.perfume.scentrev.service.ScentRevCatalogBatchImportService;

/** Full brand snapshot, then only two small brands; no full-catalog import, cleanup or test-wide transaction. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.catalog-batch-db-live-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCatalogBatchDatabaseSmokeTests {
    @Autowired private ScentRevCatalogBatchImportService importer;
    @Autowired private CatalogImportBrandProgressRepository progress;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void persistsOneSnapshotAndImportsOnlyTwoBrandsReportingOneToFiveFragrances() {
        var priorCreed = storedCreed();
        var priorCreedLinks = creedLinkIds();
        var run = importer.createRun(); // One explicit full brand listing; no fragrance imports in createRun.
        ScentRevCatalogBatchManualTests.printSummary(run); // Preserve the run ID even if selection/assertions fail.
        var selected = progress.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(run.runId(), CatalogImportBrandStatus.PENDING)
                .stream().filter(brand -> brand.getReportedFragranceCount() != null
                        && brand.getReportedFragranceCount() >= 1 && brand.getReportedFragranceCount() <= 5)
                .limit(2).toList();
        assertThat(selected).as("Two snapshot brands reporting 1-5 fragrances are required; no large-brand fallback").hasSize(2);
        for (var brand : selected) {
            System.out.printf("Selected small brand: %s (%d reported fragrances)%n",
                    brand.getBrandSlug(), brand.getReportedFragranceCount());
        }
        var slugs = selected.stream().map(brand -> brand.getBrandSlug()).toList();
        var batch = importer.processSelectedBatch(run.runId(), slugs); // One bounded batch, no extra catalog listing or retry.
        ScentRevCatalogBatchManualTests.printBatch(batch);
        assertThat(batch.brandsSelected()).isEqualTo(2);
        assertThat(batch.brandsFailedThisBatch()).as("Safe diagnostics printed above; independent commits remain").isZero();
        assertThat(batch.brandsCompletedThisBatch()).isEqualTo(2);
        assertThat(batch.totalRunPending()).isEqualTo(run.totalBrands() - 2);
        assertThat(batch.run().totalReportedFragrances()).isEqualTo(run.totalReportedFragrances());
        assertThat(batch.run().brandsWithUnknownCount()).isEqualTo(run.brandsWithUnknownCount());
        var completed = progress.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(run.runId(), CatalogImportBrandStatus.COMPLETED);
        assertThat(completed.stream().map(brand -> brand.getBrandSlug()).toList()).containsExactlyElementsOf(slugs);
        for (var brand : completed) {
            Long storedCount = jdbc.queryForObject("""
                    select count(*) from perfumes p join brands b on b.id = p.brand_id where b.brand_slug = ?
                    """, Long.class, brand.getBrandSlug());
            assertThat(storedCount).as("Persisted perfumes for %s, including older additive rows", brand.getBrandSlug())
                    .isGreaterThanOrEqualTo(brand.getProcessedFragranceCount());
            assertThat(brand.getAttemptCount()).isEqualTo(1);
        }
        assertThat(storedCreed()).as("Previously committed Creed/Aventus IDs and identifiers remain").containsAll(priorCreed);
        var afterLinks = creedLinkIds();
        priorCreedLinks.forEach((table, ids) -> assertThat(afterLinks.get(table)).containsAll(ids));
        assertThat(batch.run().brandsRunning()).isZero();
        assertThat(batch.run().batchActive()).isFalse();
    }

    private List<StoredCreed> storedCreed() {
        return jdbc.query("""
                select p.id, p.fragrance_slug, p.scentrev_public_id from perfumes p
                join brands b on b.id = p.brand_id where b.brand_slug = 'creed' order by p.id
                """, (row, index) -> new StoredCreed(row.getLong("id"), row.getString("fragrance_slug"), row.getString("scentrev_public_id")));
    }

    private Map<String, List<Long>> creedLinkIds() {
        var links = new HashMap<String, List<Long>>();
        for (String table : List.of("perfume_perfumers", "perfume_notes", "perfume_accords")) {
            links.put(table, jdbc.queryForList("select link.id from " + table
                    + " link join perfumes p on p.id = link.perfume_id join brands b on b.id = p.brand_id where b.brand_slug = ?",
                    Long.class, "creed"));
        }
        return links;
    }

    private record StoredCreed(long id, String fragranceSlug, String publicId) { }
}
