package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.ScentRevCatalogBrandDiscoveryServiceTests.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.service.ScentRevBrandImportException.Stage;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ScentRevCatalogPhase1ImportServiceTests {

    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevBrandPhase1ImportService brandImporter = mock(ScentRevBrandPhase1ImportService.class);
    private final ScentRevCatalogPhase1ImportService service = new ScentRevCatalogPhase1ImportService(
            new ScentRevCatalogBrandDiscoveryService(client), brandImporter);

    @Test
    void zeroBrandsReturnEmptySummaryWithoutCallingBrandImporter() {
        when(client.listBrands(0)).thenReturn(page(0, false));
        var result = service.importCatalog();
        assertThat(result.rawBrandCount()).isZero();
        assertThat(result.uniqueBrandCount()).isZero();
        assertThat(result.duplicateBrandCount()).isZero();
        assertThat(result.brandsAttempted()).isZero();
        assertThat(result.brandsSucceeded()).isZero();
        assertThat(result.brandsFailed()).isZero();
        assertThat(result.totalFragrancesDiscovered()).isZero();
        assertThat(result.totalUniqueFragrances()).isZero();
        assertThat(result.totalFragrancesProcessed()).isZero();
        verifyNoInteractions(brandImporter);
    }

    @Test
    void oneBrandDelegatesToExistingImporterAndAggregatesItsSummary() {
        when(client.listBrands(0)).thenReturn(page(0, false, "creed"));
        var brand = brandResult("creed", 3, 1);
        when(brandImporter.importBrand("creed")).thenReturn(brand);
        var result = service.importCatalog();
        assertThat(result.brandsAttempted()).isEqualTo(1);
        assertThat(result.brandsSucceeded()).isEqualTo(1);
        assertThat(result.brandsFailed()).isZero();
        assertThat(result.totalFragrancesDiscovered()).isEqualTo(4);
        assertThat(result.totalUniqueFragrances()).isEqualTo(3);
        assertThat(result.totalFragrancesProcessed()).isEqualTo(3);
        assertThat(result.successfulBrands()).containsExactly(brand);
        verify(brandImporter).importBrand("creed");
        verifyNoMoreInteractions(brandImporter);
        verify(client).listBrands(0);
        verifyNoMoreInteractions(client); // Catalog service never calls fragrance search or profiles.
    }

    @Test
    void duplicateBrandsAcrossPagesImportOnceInFirstSeenOrderAfterAllDiscovery() {
        when(client.listBrands(0)).thenReturn(page(0, true, "creed", "guerlain"));
        when(client.listBrands(10)).thenReturn(page(10, false, "guerlain", "zara"));
        var thread = Thread.currentThread();
        when(brandImporter.importBrand(anyString())).thenAnswer(call -> {
            assertThat(Thread.currentThread()).isSameAs(thread);
            return brandResult(call.getArgument(0), 2, 1);
        });
        var result = service.importCatalog();
        assertThat(result.rawBrandCount()).isEqualTo(4);
        assertThat(result.uniqueBrandCount()).isEqualTo(3);
        assertThat(result.duplicateBrandCount()).isEqualTo(1);
        assertThat(result.brandsAttempted()).isEqualTo(3);
        assertThat(result.brandsSucceeded()).isEqualTo(3);
        assertThat(result.brandsFailed()).isZero();
        assertThat(result.totalFragrancesDiscovered()).isEqualTo(9);
        assertThat(result.totalUniqueFragrances()).isEqualTo(6);
        assertThat(result.totalFragrancesProcessed()).isEqualTo(6);
        var order = inOrder(client, brandImporter);
        order.verify(client).listBrands(0);
        order.verify(client).listBrands(10);
        order.verify(brandImporter).importBrand("creed");
        order.verify(brandImporter).importBrand("guerlain");
        order.verify(brandImporter).importBrand("zara");
        verifyNoMoreInteractions(client, brandImporter);
    }

    @Test
    void validBrandWithNoDiscoverableFragrancesIsSuccessfulAndDoesNotCreateRowsHere() {
        when(client.listBrands(0)).thenReturn(page(0, false, "empty-brand"));
        when(brandImporter.importBrand("empty-brand")).thenReturn(brandResult("empty-brand", 0, 0));
        var result = service.importCatalog();
        assertThat(result.brandsSucceeded()).isEqualTo(1);
        assertThat(result.brandsFailed()).isZero();
        assertThat(result.totalFragrancesProcessed()).isZero();
        assertThat(result.totalUniqueFragrances()).isZero();
    }

    @Test
    void oneBrandFailureIsRecordedAndLaterBrandStillRunsWithPartialCommitCounts() {
        when(client.listBrands(0)).thenReturn(page(0, false, "creed", "problem-brand", "zara"));
        when(brandImporter.importBrand("creed")).thenReturn(brandResult("creed", 2, 0));
        var failedDiscovery = brandResult("problem-brand", 4, 1).discovery();
        when(brandImporter.importBrand("problem-brand")).thenThrow(ScentRevBrandImportException.importing(
                Stage.PROFILE, failedDiscovery, 1, "problem-brand-fragrance-1", "MCP profile request failed."));
        when(brandImporter.importBrand("zara")).thenReturn(brandResult("zara", 3, 0));
        var result = service.importCatalog();
        assertThat(result.brandsAttempted()).isEqualTo(3);
        assertThat(result.brandsSucceeded()).isEqualTo(2);
        assertThat(result.brandsFailed()).isEqualTo(1);
        assertThat(result.totalFragrancesDiscovered()).isEqualTo(10);
        assertThat(result.totalUniqueFragrances()).isEqualTo(9);
        assertThat(result.totalFragrancesProcessed()).isEqualTo(6); // Includes the failed brand's earlier commit.
        assertThat(result.failedBrands()).singleElement().satisfies(failure -> {
            assertThat(failure.brandSlug()).isEqualTo("problem-brand");
            assertThat(failure.brandPosition()).isEqualTo(2);
            assertThat(failure.totalBrands()).isEqualTo(3);
            assertThat(failure.errorCategory()).isEqualTo("PROFILE");
            assertThat(failure.successfullyProcessedCount()).isEqualTo(1);
            assertThat(failure.discovery()).isEqualTo(failedDiscovery);
        });
        var order = inOrder(brandImporter);
        order.verify(brandImporter).importBrand("creed");
        order.verify(brandImporter).importBrand("problem-brand");
        order.verify(brandImporter).importBrand("zara");
        verifyNoMoreInteractions(brandImporter);
    }

    @Test
    void multipleFailuresIncludeKnownPartialDiscoveryAndUnexpectedFailureWithoutSecrets() {
        when(client.listBrands(0)).thenReturn(page(0, false, "partial-brand", "unknown-brand", "creed"));
        var partial = brandResult("partial-brand", 2, 1).discovery();
        when(brandImporter.importBrand("partial-brand")).thenThrow(ScentRevBrandImportException.discovery(partial, 10,
                "MCP search failed."));
        when(brandImporter.importBrand("unknown-brand")).thenThrow(new IllegalStateException("fake-secret",
                new IllegalArgumentException("Bearer fake-password")));
        when(brandImporter.importBrand("creed")).thenReturn(brandResult("creed", 1, 0));
        var result = service.importCatalog();
        assertThat(result.brandsFailed()).isEqualTo(2);
        assertThat(result.brandsSucceeded()).isEqualTo(1);
        assertThat(result.totalFragrancesDiscovered()).isEqualTo(4);
        assertThat(result.totalUniqueFragrances()).isEqualTo(3);
        assertThat(result.totalFragrancesProcessed()).isEqualTo(1);
        assertThat(result.failedBrands()).extracting(ScentRevCatalogBrandFailure::brandSlug)
                .containsExactly("partial-brand", "unknown-brand");
        assertThat(result.failedBrands().get(0).message()).contains("Discovery offset: 10");
        assertThat(result.failedBrands().get(1).discovery()).isNull();
        assertThat(result.failedBrands().get(1).errorCategory()).isEqualTo("BRAND_IMPORT");
        assertThat(result.failedBrands().toString()).doesNotContain("fake-secret", "Bearer", "fake-password");
        assertThatThrownBy(() -> result.failedBrands().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.successfulBrands().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void inconsistentReturnedBrandResultIsRecordedWithoutMiscountingOrStoppingLaterBrands() {
        when(client.listBrands(0)).thenReturn(page(0, false, "one", "two", "three", "four"));
        when(brandImporter.importBrand("one")).thenReturn(null);
        when(brandImporter.importBrand("two")).thenReturn(brandResult("wrong-brand", 1, 0));
        var three = brandResult("three", 2, 0);
        when(brandImporter.importBrand("three")).thenReturn(new ScentRevBrandImportResult(three.discovery(), 1));
        when(brandImporter.importBrand("four")).thenReturn(brandResult("four", 1, 0));
        var result = service.importCatalog();
        assertThat(result.brandsFailed()).isEqualTo(3);
        assertThat(result.brandsSucceeded()).isEqualTo(1);
        assertThat(result.failedBrands()).extracting(ScentRevCatalogBrandFailure::errorCategory)
                .containsOnly("INVALID_BRAND_RESULT");
    }

    @Test
    void discoveryFailureAbortsBeforeAnyBrandImport() {
        when(client.listBrands(0)).thenThrow(new IllegalStateException("fake-secret"));
        assertThatThrownBy(service::importCatalog).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageNotContaining("fake-secret").hasNoCause();
        verifyNoInteractions(brandImporter);
    }

    @Test
    void explicitRerunRevisitsSuccessfulBrandsAndReliesOnExistingIdempotency() {
        when(client.listBrands(0)).thenReturn(page(0, false, "creed", "zara"));
        when(brandImporter.importBrand("creed")).thenReturn(brandResult("creed", 1, 0));
        when(brandImporter.importBrand("zara")).thenThrow(new IllegalStateException("fake-secret"))
                .thenReturn(brandResult("zara", 1, 0));
        assertThat(service.importCatalog().brandsFailed()).isEqualTo(1);
        var second = service.importCatalog();
        assertThat(second.brandsFailed()).isZero();
        assertThat(second.brandsSucceeded()).isEqualTo(2);
        verify(brandImporter, times(2)).importBrand("creed");
        verify(brandImporter, times(2)).importBrand("zara");
        verifyNoMoreInteractions(brandImporter);
    }

    @Test
    void brandLevelLogsIncludeProgressButNoRawExceptionMessagesPayloadsOrCauses() {
        var logger = (Logger) LoggerFactory.getLogger(ScentRevCatalogPhase1ImportService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(client.listBrands(0)).thenReturn(page(0, false, "creed", "bad-brand"));
            when(brandImporter.importBrand("creed")).thenReturn(brandResult("creed", 1, 0));
            when(brandImporter.importBrand("bad-brand")).thenThrow(new IllegalStateException("fake-api-key-and-db-password"));
            service.importCatalog();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("[1/2] Importing brand: creed"))
                    .anyMatch(message -> message.contains("Completed brand creed: 1 fragrances"))
                    .anyMatch(message -> message.contains("[2/2] Failed brand bad-brand"));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("fake-api-key-and-db-password");
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    static ScentRevBrandImportResult brandResult(String slug, int uniqueCount, int duplicates) {
        var fragrances = IntStream.range(0, uniqueCount)
                .mapToObj(i -> new Fragrance(slug + "-fragrance-" + i, "id-" + slug + "-" + i, "Name", slug)).toList();
        return new ScentRevBrandImportResult(new ScentRevBrandDiscoveryResult(slug, 1,
                uniqueCount + duplicates, duplicates, fragrances), uniqueCount);
    }
}
