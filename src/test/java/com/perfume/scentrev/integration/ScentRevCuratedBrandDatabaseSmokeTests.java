package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.curated.*;
import com.perfume.scentrev.progress.CatalogImportBrandProgressRepository;
import com.perfume.scentrev.progress.CatalogImportBrandStatus;

/** No large-brand fallback: an explicit verified parent reporting 1-5 fragrances is mandatory. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.curated-db-live-test", matches = "true")
@EnabledIf(value = "hasExplicitVerifiedSmallBrand", disabledReason = "Approve and explicitly select a verified curated brand with a known count of 1-5; no large-brand fallback")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCuratedBrandDatabaseSmokeTests {
    @Autowired private ScentRevCuratedBrandImportService curated;
    @Autowired private CatalogImportBrandProgressRepository progress;

    @Test
    void importsOnlyTheExplicitVerifiedSmallSnapshotBrand() {
        var selected = explicitSmallBrand().orElseThrow();
        var created = curated.createCuratedRun();
        ScentRevCatalogBatchManualTests.printCuratedPlan(created);
        ScentRevCatalogBatchManualTests.printSummary(created.run());
        var batch = curated.processSmallSmokeBatch(created.run().runId(), selected.brandSlug());
        ScentRevCatalogBatchManualTests.printBatch(batch);
        assertThat(batch.brandsSelected()).isEqualTo(1);
        assertThat(batch.brandsFailedThisBatch()).as("Earlier commits remain; retry is explicit").isZero();
        assertThat(batch.brandsCompletedThisBatch()).isEqualTo(1);
        assertThat(batch.totalRunPending()).isEqualTo(created.run().totalBrands() - 1);
        assertThat(batch.run().brandsRunning()).isZero();
        assertThat(batch.run().batchActive()).isFalse();
        var completed = progress.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(created.run().runId(), CatalogImportBrandStatus.COMPLETED);
        assertThat(completed).extracting(row -> row.getBrandSlug()).containsExactly(selected.brandSlug());
    }

    static boolean hasExplicitVerifiedSmallBrand() { return explicitSmallBrand().isPresent(); }

    private static Optional<CuratedBrandDefinition> explicitSmallBrand() {
        String key = System.getProperty("scentrev.curated-smoke-brand-key", "");
        return new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load().parents().stream()
                .filter(parent -> parent.canonicalBrandKey().equals(key) && parent.importable()
                        && parent.reportedFragranceCount() != null && parent.reportedFragranceCount() >= 1
                        && parent.reportedFragranceCount() <= 5).findFirst();
    }
}
