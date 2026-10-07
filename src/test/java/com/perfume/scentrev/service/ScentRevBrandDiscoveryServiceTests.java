package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;

class ScentRevBrandDiscoveryServiceTests {

    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevBrandDiscoveryService service = new ScentRevBrandDiscoveryService(client);

    @Test
    void singleFinalPageStopsWithoutExtraCall() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, "creed-aventus", "creed-viking"));
        var result = service.discoverBrandFragrances("creed");
        assertThat(result.brandSlug()).isEqualTo("creed");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.discoveredResultCount()).isEqualTo(2);
        assertThat(result.uniqueFragranceCount()).isEqualTo(2);
        assertThat(result.duplicateDiscoveryCount()).isZero();
        verify(client).searchFragrancesFiltered("creed", 0);
        verifyNoMoreInteractions(client);
    }

    @Test
    void fullPagesAndFinalPartialPageUseOffsetsAndPreserveFirstSeenOrder() {
        String[] first = IntStream.range(0, 10).mapToObj(i -> "creed-first-" + i).toArray(String[]::new);
        String[] second = IntStream.range(0, 10).mapToObj(i -> "creed-second-" + i).toArray(String[]::new);
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, first));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, true, second));
        when(client.searchFragrancesFiltered("creed", 20)).thenReturn(page(20, false, "creed-last"));
        var result = service.discoverBrandFragrances("creed");
        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.discoveredResultCount()).isEqualTo(21);
        var expected = new ArrayList<>(Arrays.asList(first));
        expected.addAll(Arrays.asList(second));
        expected.add("creed-last");
        assertThat(result.fragrances()).extracting(Fragrance::fragranceSlug).containsExactlyElementsOf(expected);
        var order = inOrder(client);
        order.verify(client).searchFragrancesFiltered("creed", 0);
        order.verify(client).searchFragrancesFiltered("creed", 10);
        order.verify(client).searchFragrancesFiltered("creed", 20);
        verifyNoMoreInteractions(client);
    }

    @Test
    void offsetAdvancesByReturnedLimitEvenWhenTruncatedPageHasFewerRows() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, "creed-b"));
        assertThat(service.discoverBrandFragrances("creed").uniqueFragranceCount()).isEqualTo(2);
        verify(client).searchFragrancesFiltered("creed", 0);
        verify(client).searchFragrancesFiltered("creed", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void duplicateSlugsWithinAndAcrossPagesAreCountedAndDeduplicated() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a", "creed-a", "creed-b"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, "creed-b", "creed-c"));
        var result = service.discoverBrandFragrances("creed");
        assertThat(result.discoveredResultCount()).isEqualTo(5);
        assertThat(result.uniqueFragranceCount()).isEqualTo(3);
        assertThat(result.duplicateDiscoveryCount()).isEqualTo(2);
        assertThat(result.fragrances()).extracting(Fragrance::fragranceSlug).containsExactly("creed-a", "creed-b", "creed-c");
        assertThatThrownBy(() -> result.fragrances().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyFinalPageIsAnEmptyCatalog() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false));
        var result = service.discoverBrandFragrances("creed");
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.fragrances()).isEmpty();
        assertThat(result.discoveredResultCount()).isZero();
        verify(client).searchFragrancesFiltered("creed", 0);
        verifyNoMoreInteractions(client);
    }

    @Test
    void echoedOffsetMustMatchNextRequestedOffset() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(0, true, "creed-b"));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("non-advancing")
                .hasMessageContaining("offset 10").satisfies(error -> {
                    var failure = (ScentRevBrandImportException) error;
                    assertThat(failure.getDiscovery().pageCount()).isEqualTo(1);
                    assertThat(failure.getDiscovery().uniqueFragranceCount()).isEqualTo(1);
                });
        verify(client).searchFragrancesFiltered("creed", 0);
        verify(client).searchFragrancesFiltered("creed", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void repeatedPageIsRejectedEvenWhenReorderedAndFinal() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a", "creed-b"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, "creed-b", "creed-a"));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("Repeated search page");
    }

    @Test
    void truncatedPageWithNoNewSlugsFailsInsteadOfContinuingIndefinitely() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a", "creed-b"));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, true, "creed-a"));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("no discovery progress");
    }

    @Test
    void emptyTruncatedPageIsRejected() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("no discovery progress");
        verify(client).searchFragrancesFiltered("creed", 0);
        verifyNoMoreInteractions(client);
    }

    @Test
    void generousPageCapStopsEvenWhenEveryPageIsNew() {
        when(client.searchFragrancesFiltered(eq("creed"), anyInt())).thenAnswer(call -> {
            int offset = call.getArgument(1);
            return page(offset, true, "creed-item-" + offset);
        });
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("safety limit")
                .satisfies(error -> assertThat(((ScentRevBrandImportException) error).getDiscovery().pageCount())
                        .isEqualTo(ScentRevBrandDiscoveryService.MAX_PAGES));
        verify(client, times(ScentRevBrandDiscoveryService.MAX_PAGES)).searchFragrancesFiltered(eq("creed"), anyInt());
        verifyNoMoreInteractions(client);
    }

    @Test
    void missingPaginationMetadataAndNullResponseAreRejected() {
        for (var response : Arrays.asList(null,
                new ScentRevFilteredSearchResponse(null, false, 0, 10, null, null),
                new ScentRevFilteredSearchResponse(List.of(), null, 0, 10, null, null),
                new ScentRevFilteredSearchResponse(List.of(), false, null, 10, null, null),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, null, null, null))) {
            when(client.searchFragrancesFiltered("creed", 0)).thenReturn(response);
            assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                    .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("Missing results or pagination");
        }
    }

    @Test
    void wrongLimitPartialResponseInconsistentCountsAndOversizedPagesAreRejected() {
        for (var response : List.of(
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 0, 0, null),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 10, 0, true),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 10, 1, null),
                page(0, false, IntStream.range(0, 11).mapToObj(i -> "creed-item-" + i).toArray(String[]::new)))) {
            when(client.searchFragrancesFiltered("creed", 0)).thenReturn(response);
            assertThatThrownBy(() -> service.discoverBrandFragrances("creed")).isInstanceOf(ScentRevBrandImportException.class);
        }
    }

    @Test
    void missingCanonicalSlugOrNullRowIsRejected() {
        for (var row : Arrays.asList(null, new Fragrance(null, null, null, null),
                new Fragrance(" ", null, null, null), new Fragrance(" creed-a", null, null, null))) {
            when(client.searchFragrancesFiltered("creed", 0)).thenReturn(new ScentRevFilteredSearchResponse(
                    Arrays.asList(row), false, 0, 10, 1, null));
            assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                    .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("canonical fragrance slug");
        }
    }

    @Test
    void suppliedSearchBrandMustMatchRequestedBrand() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(new ScentRevFilteredSearchResponse(
                List.of(new Fragrance("another-a", "id", "A", "another")), false, 0, 10, 1, null));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("another brand");
    }

    @Test
    void duplicateSlugWithConflictingPublicIdFailsDiscovery() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(new ScentRevFilteredSearchResponse(List.of(
                new Fragrance("creed-a", "id-one", "A", "creed"),
                new Fragrance("creed-a", "id-two", "A", "creed")), false, 0, 10, 2, null));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("Conflicting public IDs");
    }

    @Test
    void mcpFailureReportsBrandOffsetAndCompletedDiscoveryWithoutRawCause() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, "creed-a"));
        when(client.searchFragrancesFiltered("creed", 10)).thenThrow(new IllegalStateException("secret-authorization"));
        assertThatThrownBy(() -> service.discoverBrandFragrances("creed"))
                .isInstanceOf(ScentRevBrandImportException.class).hasMessageContaining("creed at offset 10")
                .hasMessageContaining("1 pages (1 rows, 1 unique)").hasNoCause()
                .hasMessageNotContaining("secret-authorization");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " creed", "creed "})
    void invalidBrandFailsBeforeNetwork(String brand) {
        assertThatThrownBy(() -> service.discoverBrandFragrances(brand)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    static ScentRevFilteredSearchResponse page(int offset, boolean truncated, String... slugs) {
        List<Fragrance> results = Arrays.stream(slugs).map(slug -> new Fragrance(slug, "id-" + slug, slug, null)).toList();
        return new ScentRevFilteredSearchResponse(results, truncated, offset, 10, results.size(), null);
    }
}
