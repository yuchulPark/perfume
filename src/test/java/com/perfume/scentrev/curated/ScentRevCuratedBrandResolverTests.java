package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.curated.ScentRevCuratedBrandConfigurationTests.definition;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus.UNRESOLVED;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevBrandListResponse;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;
import com.perfume.scentrev.curated.ScentRevCuratedBrandResolver.Status;

class ScentRevCuratedBrandResolverTests {
    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevCuratedBrandResolver resolver = new ScentRevCuratedBrandResolver(client);
    private final CuratedBrandDefinition parent = definition("parent", null, true, UNRESOLVED, 1, "Frederic Malle");

    @Test
    void exactSingleResultReportsCandidateWithoutApprovingImportOrMakingAnotherRequest() {
        responds(false, new CatalogBrand("provider-actual-slug", "Frederic Malle", 42L));
        var result = resolver.resolve(parent);
        assertThat(result.status()).isEqualTo(Status.CANDIDATE);
        assertThat(result.candidates()).extracting(CatalogBrand::brandSlug).containsExactly("provider-actual-slug");
        assertThat(parent.brandSlug()).isNull();
        assertThat(parent.importable()).isFalse();
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void noResultIsUnresolvedWithoutFallback() {
        responds(false);
        assertThat(resolver.resolve(parent).status()).isEqualTo(Status.UNRESOLVED);
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void multipleCandidatesAreAmbiguousEvenWhenOneHasAnExactName() {
        responds(false, new CatalogBrand("first", "Frederic Malle", null), new CatalogBrand("second", "Frederic Malle Home", null));
        assertThat(resolver.resolve(parent).status()).isEqualTo(Status.AMBIGUOUS);
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void truncatedFirstPageIsAmbiguousAndNeverTriggersPagination() {
        responds(true, new CatalogBrand("first", "Frederic Malle", null));
        assertThat(resolver.resolve(parent).status()).isEqualTo(Status.AMBIGUOUS);
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void substringOnlyResultCannotBecomeStrongCandidate() {
        responds(false, new CatalogBrand("first", "Frederic Malle Home", null));
        assertThat(resolver.resolve(parent).status()).isEqualTo(Status.UNRESOLVED);
    }

    @Test
    void missingTruncationMetadataCannotProveUniqueCanonicalCandidate() {
        when(client.searchBrands("Frederic Malle", 10)).thenReturn(new ScentRevBrandListResponse(
                List.of(new CatalogBrand("first", "Frederic Malle", null)), null, 0, 10, 1));
        assertThat(resolver.resolve(parent).status()).isEqualTo(Status.AMBIGUOUS);
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void ambiguousRawIdentityWithNoReviewedQueryMakesZeroRequests() {
        var unresolved = new CuratedBrandDefinition("unreviewed", "Unreviewed source", null, null,
                true, UNRESOLVED, null, null, null, List.of(new CuratedBrandDefinition.SourceEntry(
                        1, "Unreviewed source", CuratedBrandDefinition.EntryType.UNRESOLVED)));
        assertThat(unresolved.resolutionQuery()).isNull();
        assertThat(resolver.resolve(unresolved).status()).isEqualTo(Status.UNRESOLVED);
        verifyNoInteractions(client);
    }

    @Test
    void verifiedAndDisabledParentsCannotBeResolvedAndChunksExcludeThem() {
        var catalog = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();
        assertThatThrownBy(() -> resolver.resolve(catalog.parents().get(0))).isInstanceOf(IllegalArgumentException.class);
        for (String key : List.of("curated-035", "curated-067", "curated-090", "curated-096", "curated-108",
                "curated-110", "curated-111", "curated-133", "curated-145", "curated-147", "curated-161")) {
            var excluded = catalog.parents().stream().filter(p -> p.canonicalBrandKey().equals(key)).findFirst().orElseThrow();
            assertThatThrownBy(() -> resolver.resolve(excluded)).isInstanceOf(IllegalArgumentException.class);
            assertThat(catalog.unresolvedParents()).doesNotContain(excluded);
        }
        assertThat(resolver.chunk(catalog, 0, 5)).isEmpty();
        assertThat(resolver.chunk(catalog, 0, 10)).isEmpty();
        assertThat(resolver.chunk(catalog, Integer.MAX_VALUE, 5)).isEmpty();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 11, 100})
    void invalidChunkSizesFailBeforeRequests(int size) {
        var catalog = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();
        assertThatThrownBy(() -> resolver.chunk(catalog, 0, size)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void negativeStartFailsBeforeRequests() {
        var catalog = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();
        assertThatThrownBy(() -> resolver.chunk(catalog, -1, 5)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void invalidCandidateResponseStopsResolution() {
        responds(false, new CatalogBrand("Bad/Slug", "Frederic Malle", null));
        assertThatThrownBy(() -> resolver.resolve(parent)).isInstanceOf(IllegalStateException.class);
        verify(client).searchBrands("Frederic Malle", 10);
        verifyNoMoreInteractions(client);
    }

    private void responds(boolean truncated, CatalogBrand... brands) {
        when(client.searchBrands("Frederic Malle", 10)).thenReturn(new ScentRevBrandListResponse(List.of(brands), truncated, 0, 10, brands.length));
    }
}
