package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevBrandListResponse;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;

class ScentRevCatalogBrandDiscoveryServiceTests {

    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevCatalogBrandDiscoveryService service = new ScentRevCatalogBrandDiscoveryService(client);

    @Test
    void onePageStopsImmediatelyAndPreservesProviderOrder() {
        when(client.listBrands(0)).thenReturn(page(0, false, "creed", "guerlain"));
        var result = service.discoverBrands();
        assertThat(result.rawBrandCount()).isEqualTo(2);
        assertThat(result.uniqueBrandCount()).isEqualTo(2);
        assertThat(result.duplicateBrandCount()).isZero();
        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.brands()).extracting(CatalogBrand::brandSlug).containsExactly("creed", "guerlain");
        verify(client).listBrands(0);
        verifyNoMoreInteractions(client);
    }

    @Test
    void multipleFullPagesAndFinalPartialPageAdvanceByOffsetPlusLimitWithoutExtraCall() {
        String[] first = IntStream.range(0, 10).mapToObj(i -> "first-" + i).toArray(String[]::new);
        String[] second = IntStream.range(0, 10).mapToObj(i -> "second-" + i).toArray(String[]::new);
        when(client.listBrands(0)).thenReturn(page(0, true, first));
        when(client.listBrands(10)).thenReturn(page(10, true, second));
        when(client.listBrands(20)).thenReturn(page(20, false, "last-brand"));
        var result = service.discoverBrands();
        assertThat(result.rawBrandCount()).isEqualTo(21);
        assertThat(result.uniqueBrandCount()).isEqualTo(21);
        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.brands().get(0).brandSlug()).isEqualTo("first-0");
        assertThat(result.brands().get(20).brandSlug()).isEqualTo("last-brand");
        var order = inOrder(client);
        order.verify(client).listBrands(0);
        order.verify(client).listBrands(10);
        order.verify(client).listBrands(20);
        verifyNoMoreInteractions(client);
    }

    @Test
    void shortTruncatedPageStillAdvancesByLimitRatherThanReturnedRows() {
        when(client.listBrands(0)).thenReturn(page(0, true, "creed"));
        when(client.listBrands(10)).thenReturn(page(10, false, "guerlain"));
        assertThat(service.discoverBrands().uniqueBrandCount()).isEqualTo(2);
        verify(client).listBrands(0);
        verify(client).listBrands(10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void trimsAndDeduplicatesCanonicalSlugsWithinAndAcrossPagesKeepingFirstName() {
        when(client.listBrands(0)).thenReturn(page(0, true, " creed ", "creed", "guerlain"));
        when(client.listBrands(10)).thenReturn(page(10, false, "guerlain", "the-new-dawn"));
        var result = service.discoverBrands();
        assertThat(result.rawBrandCount()).isEqualTo(5);
        assertThat(result.uniqueBrandCount()).isEqualTo(3);
        assertThat(result.duplicateBrandCount()).isEqualTo(2);
        assertThat(result.brands()).extracting(CatalogBrand::brandSlug).containsExactly("creed", "guerlain", "the-new-dawn");
        assertThat(result.brands().get(0).brandName()).isEqualTo("Name:  creed ");
        assertThatThrownBy(() -> result.brands().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unusualDisplayNamesArePreservedWithoutChangingCanonicalIdentity() {
        when(client.listBrands(0)).thenReturn(new ScentRevBrandListResponse(
                List.of(new CatalogBrand("the-new-dawn", "Новая Заря (The New Dawn)")), false, 0, 10, 1));
        assertThat(service.discoverBrands().brands()).singleElement()
                .satisfies(brand -> assertThat(brand.brandName()).isEqualTo("Новая Заря (The New Dawn)"));
    }

    @Test
    void emptyCatalogIsValidAndMakesOnlyOneCall() {
        when(client.listBrands(0)).thenReturn(page(0, false));
        var result = service.discoverBrands();
        assertThat(result.brands()).isEmpty();
        assertThat(result.rawBrandCount()).isZero();
        assertThat(result.pageCount()).isEqualTo(1);
        verify(client).listBrands(0);
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "brand/name", "brand name", "Brand", "-brand", "brand-", "brand--name", "brand_name", "brand\nname"})
    void blankOrMalformedSlugIsRejectedWithoutEchoingIt(String slug) {
        when(client.listBrands(0)).thenReturn(new ScentRevBrandListResponse(
                List.of(new CatalogBrand(slug, "Display name")), false, 0, 10, 1));
        assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageContaining("Blank or malformed canonical brand slug");
    }

    @Test
    void oversizedSlugAndNullRowAreRejected() {
        for (var brand : Arrays.asList(null, new CatalogBrand("a".repeat(256), "Name"))) {
            when(client.listBrands(0)).thenReturn(new ScentRevBrandListResponse(Arrays.asList(brand), false, 0, 10, 1));
            assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                    .hasMessageContaining("malformed canonical brand slug");
        }
    }

    @Test
    void repeatedPageIsRejectedEvenIfReorderedAndMarkedFinal() {
        when(client.listBrands(0)).thenReturn(page(0, true, "creed", "guerlain"));
        when(client.listBrands(10)).thenReturn(page(10, false, "guerlain", "creed"));
        assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageContaining("Repeated brand page");
    }

    @Test
    void nonAdvancingEchoedOffsetIsRejectedWithProgress() {
        when(client.listBrands(0)).thenReturn(page(0, true, "creed"));
        when(client.listBrands(10)).thenReturn(page(0, true, "guerlain"));
        assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageContaining("non-advancing").hasMessageContaining("offset 10").satisfies(error -> {
                    var failure = (ScentRevCatalogDiscoveryException) error;
                    assertThat(failure.getFailedOffset()).isEqualTo(10);
                    assertThat(failure.getPageCount()).isEqualTo(1);
                    assertThat(failure.getRawBrandCount()).isEqualTo(1);
                    assertThat(failure.getUniqueBrandCount()).isEqualTo(1);
                });
    }

    @Test
    void continuationWithoutNewBrandsOrWithEmptyPageIsRejected() {
        for (var second : List.of(page(10, true, "creed"), page(10, true))) {
            when(client.listBrands(0)).thenReturn(page(0, true, "creed", "guerlain"));
            when(client.listBrands(10)).thenReturn(second);
            assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                    .hasMessageContaining("no discovery progress");
        }
    }

    @Test
    void missingPaginationMetadataOrNullResponseIsRejected() {
        for (var response : Arrays.asList(null,
                new ScentRevBrandListResponse(null, false, 0, 10, null),
                new ScentRevBrandListResponse(List.of(), null, 0, 10, null),
                new ScentRevBrandListResponse(List.of(), false, null, 10, null),
                new ScentRevBrandListResponse(List.of(), false, 0, null, null))) {
            when(client.listBrands(0)).thenReturn(response);
            assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                    .hasMessageContaining("Missing brands or pagination metadata");
        }
    }

    @Test
    void wrongLimitInconsistentCountAndOversizedPageAreRejected() {
        for (var response : List.of(new ScentRevBrandListResponse(List.of(), false, 0, 0, 0),
                new ScentRevBrandListResponse(List.of(), false, 0, 10, 1),
                page(0, false, IntStream.range(0, 11).mapToObj(i -> "brand-" + i).toArray(String[]::new)))) {
            when(client.listBrands(0)).thenReturn(response);
            assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class);
        }
    }

    @Test
    void safetyLimitStopsNewPagesWithoutUsingAnyHistoricalCatalogCount() {
        when(client.listBrands(anyInt())).thenAnswer(call -> {
            int offset = call.getArgument(0);
            return page(offset, true, "brand-" + offset);
        });
        assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageContaining("safety limit").satisfies(error ->
                        assertThat(((ScentRevCatalogDiscoveryException) error).getPageCount())
                                .isEqualTo(ScentRevCatalogBrandDiscoveryService.MAX_PAGES));
        verify(client, times(ScentRevCatalogBrandDiscoveryService.MAX_PAGES)).listBrands(anyInt());
        verifyNoMoreInteractions(client);
    }

    @Test
    void mcpFailureIncludesSafeProgressAndNeverRawMessageOrCause() {
        when(client.listBrands(0)).thenReturn(page(0, true, "creed"));
        when(client.listBrands(10)).thenThrow(new IllegalStateException("fake-secret-authorization"));
        assertThatThrownBy(service::discoverBrands).isInstanceOf(ScentRevCatalogDiscoveryException.class)
                .hasMessageContaining("offset 10 after 1 pages (1 raw, 1 unique brands)")
                .hasMessageNotContaining("fake-secret-authorization").hasNoCause();
    }

    static ScentRevBrandListResponse page(int offset, boolean truncated, String... slugs) {
        var brands = Arrays.stream(slugs).map(slug -> new CatalogBrand(slug, "Name: " + slug)).toList();
        return new ScentRevBrandListResponse(brands, truncated, offset, 10, brands.size());
    }
}
