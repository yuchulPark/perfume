package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import com.perfume.scentrev.service.ScentRevCatalogBatchImportResult;
import com.perfume.scentrev.service.ScentRevCatalogBatchImportService;
import com.perfume.scentrev.service.ScentRevCatalogRunSummary;
import com.perfume.scentrev.curated.ScentRevCuratedBrandImportService;

/** One explicitly selected action per invocation. No automatic loop, startup import or test-wide transaction. */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.catalog-batch-manual-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCatalogBatchManualTests {
    @Autowired private ScentRevCatalogBatchImportService importer;
    @Autowired private ScentRevCuratedBrandImportService curated;

    @Test
    void performsExactlyOneExplicitCatalogOperation() {
        String action = System.getProperty("scentrev.catalog-action", "");
        switch (action) {
            case "create" -> {
                requireApiKey();
                printSummary(importer.createRun());
            }
            case "create-curated" -> {
                var created = curated.createCuratedRun(); // Configuration only; zero discovery/API requests.
                printCuratedPlan(created);
                printSummary(created.run());
            }
            case "inspect" -> printSummary(importer.getRun(runId()));
            case "batch" -> {
                requireApiKey();
                int size = Integer.parseInt(System.getProperty("scentrev.catalog-batch-size", "1"));
                var result = curated.processNextBatch(runId(), size);
                printBatch(result);
                assertThat(result.brandsFailedThisBatch()).as("Failures printed above; successful commits remain; retry explicitly").isZero();
            }
            case "retry" -> printSummary(importer.retryFailedBrands(runId()));
            case "recover" -> {
                System.out.println("Explicit recovery: the previous executor must already be stopped.");
                printSummary(importer.recoverInterruptedRun(runId()));
            }
            default -> throw new IllegalArgumentException("Set scentrev.catalog-action to create-curated, inspect, batch, retry, or recover (create is historical full discovery).");
        }
    }

    private static Long runId() {
        String value = System.getProperty("scentrev.catalog-run-id");
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Set scentrev.catalog-run-id to the persisted run ID."); }
        long id = Long.parseLong(value);
        if (id <= 0) { throw new IllegalArgumentException("Catalog run ID must be positive."); }
        return id;
    }

    private static void requireApiKey() {
        String key = System.getenv("SCENTREV_API_KEY");
        assertThat(key != null && !key.isBlank()).as("SCENTREV_API_KEY is required for create/batch; its value is never printed").isTrue();
    }

    static void printSummary(ScentRevCatalogRunSummary run) {
        System.out.printf("ScentRev catalog run summary:%nrun ID: %d%nstatus: %s%nbrands planned: %d%n"
                        + "reported fragrances (known counts): %d%nbrands with unknown counts: %d%n"
                        + "completed: %d%nfailed: %d%npending: %d%nrunning/reserved: %d%n"
                        + "processed fragrances (latest recorded attempts): %d%nbatch active: %s%nrun complete: %s%n",
                run.runId(), run.status(), run.totalBrands(), run.totalReportedFragrances(), run.brandsWithUnknownCount(),
                run.brandsCompleted(), run.brandsFailed(), run.brandsPending(), run.brandsRunning(),
                run.processedFragrances(), run.batchActive(), run.runComplete());
    }

    static void printCuratedPlan(ScentRevCuratedBrandImportService.CuratedRun created) {
        var catalog = created.catalog();
        System.out.printf("Curated ScentRev run:%nraw source labels: %d%nnormalized parent brands: %d%n"
                        + "verified enabled brands (unique slugs): %d%nunresolved brands: %d%ndisabled brands: %d%nprogress rows created: %d%n",
                catalog.rawLabels().size(), catalog.parents().size(), catalog.verifiedBrands().size(),
                catalog.unresolvedParents().size(), catalog.disabledCount(), created.run().totalBrands());
        catalog.unresolvedParents().forEach(parent -> System.out.printf("UNRESOLVED %s [%s]: %s%n",
                parent.canonicalDisplayName(), parent.canonicalBrandKey(),
                parent.sources().stream().map(source -> source.rawLabel()).toList()));
    }

    static void printBatch(ScentRevCatalogBatchImportResult batch) {
        System.out.printf("ScentRev catalog batch summary:%nrun ID: %d%nbrands selected: %d%ncompleted this batch: %d%n"
                        + "failed this batch: %d%nfragrances processed this batch: %d%n",
                batch.runId(), batch.brandsSelected(), batch.brandsCompletedThisBatch(), batch.brandsFailedThisBatch(),
                batch.fragrancesProcessedThisBatch());
        batch.failedBrands().forEach(failure -> System.out.printf("Failed %s: %s%n", failure.brandSlug(), failure.message()));
        printSummary(batch.run());
    }
}
