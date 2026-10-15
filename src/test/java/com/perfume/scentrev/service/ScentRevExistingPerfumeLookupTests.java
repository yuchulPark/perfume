package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.service.ScentRevSkipExistingImportTests.identity;
import java.util.List;
import java.util.stream.Stream;
import com.perfume.repository.PerfumeImportIdentityProjection;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ScentRevExistingPerfumeLookupTests {
    private final PerfumeRepository perfumes = mock(PerfumeRepository.class);
    private final ScentRevExistingPerfumeLookup lookup = new ScentRevExistingPerfumeLookup(perfumes);
    private final ScentRevSingleBrandImportService.ValidatedBrand brand = new ScentRevSingleBrandImportService.ValidatedBrand(2L, "Creed", "Creed", "creed");

    @Test
    void exactSameBrandIdentifiersAreSkippedRegardlessOfDisplayNameOrMissingSearchBrand() {
        when(perfumes.findImportIdentities(anyCollection(), anyCollection())).thenReturn(List.of(identity(1, "slug", "id", 2, "creed")));
        var result = lookup.find(brand, List.of(new Fragrance("slug", "id", "Renamed or duplicated perfume name", null)));
        assertThat(result.existingPublicIdsBySlug()).containsExactly(entry("slug", "id"));
        assertThat(result.newCandidateSlugs()).isEmpty();
        verify(perfumes).findImportIdentities(List.of("slug"), List.of("id")); verifyNoMoreInteractions(perfumes);
    }

    @Test
    void aMissingPublicIdAllowsOnlyAnExactSlugAndBrandMatch() {
        when(perfumes.findImportIdentities(anyCollection(), anyCollection())).thenReturn(List.of(identity(1, "slug", "known", 2, "creed")));
        var result = lookup.find(brand, List.of(new Fragrance("slug", null, "Name", "creed")));
        assertThat(result.existingPublicIdsBySlug()).containsExactly(entry("slug", "known"));
        verify(perfumes).findImportIdentities(List.of("slug"), List.of()); verifyNoMoreInteractions(perfumes);
    }

    @ParameterizedTest
    @MethodSource("uncertainMatches")
    void mismatchesAreNeitherSilentlySkippedNorReportedAsNew(List<PerfumeImportIdentityProjection> rows, Fragrance candidate) {
        when(perfumes.findImportIdentities(anyCollection(), anyCollection())).thenReturn(rows);
        var result = lookup.find(brand, List.of(candidate));
        assertThat(result.existingPublicIdsBySlug()).isEmpty();
        assertThat(result.newCandidateSlugs()).isEmpty();
        verify(perfumes).findImportIdentities(anyCollection(), anyCollection()); verifyNoMoreInteractions(perfumes);
    }

    static Stream<Arguments> uncertainMatches() {
        var normal = new Fragrance("slug", "id", "Name", "creed");
        return Stream.of(
                Arguments.of(List.of(identity(1, "slug", "other", 2, "creed")), normal),
                Arguments.of(List.of(identity(1, "other", "id", 2, "creed")), normal),
                Arguments.of(List.of(identity(1, "slug", "other", 2, "creed"), identity(2, "other", "id", 2, "creed")), normal),
                Arguments.of(List.of(identity(1, "slug", "id", 9, "foreign")), normal),
                Arguments.of(List.of(identity(1, "slug", "id", 2, "foreign")), normal),
                Arguments.of(List.of(identity(1, "slug", "id", 9, "creed")), normal),
                Arguments.of(List.of(identity(1, "slug", "id", 2, "creed")), new Fragrance("slug", "id", "Name", "foreign")),
                Arguments.of(List.of(identity(1, "slug", null, 2, "creed")), new Fragrance("slug", null, "Name", null)),
                Arguments.of(List.of(identity(1, "slug", " ", 2, "creed")), new Fragrance("slug", null, "Name", null)),
                Arguments.of(List.of(new ScentRevSkipExistingImportTests.Identity(null, "id", "slug", 2L, "creed")), normal),
                Arguments.of(List.of(identity(1, "slug", "id", 2, "creed"), identity(2, "slug", "id", 2, "creed")), normal));
    }

    @Test
    void noIdentityHitMeansANewCandidateWithoutUsingItsName() {
        when(perfumes.findImportIdentities(anyCollection(), anyCollection())).thenReturn(List.of());
        var result = lookup.find(brand, List.of(new Fragrance("brand-new", "new-id", "Aventus", "creed")));
        assertThat(result.existingPublicIdsBySlug()).isEmpty();
        assertThat(result.newCandidateSlugs()).containsExactly("brand-new");
    }

    @Test
    void emptyCandidatePageNeedsNoQuery() {
        assertThat(lookup.find(brand, List.of()).existingPublicIdsBySlug()).isEmpty();
        verifyNoInteractions(perfumes);
    }
}
