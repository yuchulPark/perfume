package com.perfume.scentrev.service;

import static com.perfume.scentrev.service.OnDemandTestStore.profile;
import static com.perfume.scentrev.service.ScentRevOnDemandException.FailureType.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import com.perfume.repository.PerfumeRepository;
import com.perfume.repository.PerfumeSearchProjection;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;
import com.perfume.scentrev.dto.ScentRevSearchResponse;
import com.perfume.scentrev.dto.ScentRevSearchResponse.Fragrance;

class ScentRevOnDemandPerfumeServiceTests {
    private final OnDemandTestStore store = new OnDemandTestStore();
    private final ScentRevOnDemandCacheAccess cache = spy(store.cache);
    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevOnDemandPerfumeService service = new ScentRevOnDemandPerfumeService(client, cache);

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t\n", "A", " A ", "\uD83D\uDC90"})
    void invalidOrTooShortQueryIsRejectedBeforeAnyIo(String query) {
        expect(INVALID_QUERY, () -> service.search(query, 5));
        verifyNoInteractions(cache, client);
    }
    @ParameterizedTest @ValueSource(ints = {0, -1, 11, Integer.MAX_VALUE})
    void invalidLimitIsRejectedBeforeAnyIo(int limit) {
        expect(INVALID_LIMIT, () -> service.search("Aventus", limit));
        verifyNoInteractions(cache, client);
    }

    @ParameterizedTest @ValueSource(strings = {"Aventus", "aVeNTus", " Aventus ", "Creed", "CREED"})
    void perfumeOrBrandNameLocalHitUsesCaseInsensitivePatternAndZeroMcpRequests(String query) throws Exception {
        String expectedPattern = query.strip().equalsIgnoreCase("Creed") ? "%creed%" : "%aventus%";
        when(store.perfumes.searchCached(eq(expectedPattern), any())).thenReturn(List.of(localRow()));
        var result = service.search(query, 5);
        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.cached()).isTrue();
            assertThat(candidate.source()).isEqualTo(PerfumeSearchCandidate.Source.LOCAL);
            assertThat(candidate.fragranceSlug()).isEqualTo("creed-aventus");
            assertThat(candidate.brandName()).isEqualTo("Creed");
        });
        String jpql = PerfumeRepository.class.getMethod("searchCached", String.class, Pageable.class).getAnnotation(Query.class).value();
        assertThat(jpql).contains("lower(p.name) like :pattern", "lower(b.name) like :pattern", " or ");
        verifyNoInteractions(client, store.mapper);
    }
    @Test void localSearchIsBoundedInTheJpaQueryAndTheReturnedResult() {
        when(store.perfumes.searchCached(anyString(), any())).thenReturn(IntStream.range(0, 15).mapToObj(i -> localRow()).toList());
        assertThat(service.search("Aventus", 3).candidates()).hasSize(3);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(store.perfumes).searchCached(eq("%aventus%"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(3);
        assertThat(page.getValue().getOffset()).isZero();
        verifyNoInteractions(client);
    }
    @Test void literalWildcardCharactersAreEscapedForLocalSubstringSearch() {
        when(client.searchFragrances("A_!%", 1)).thenReturn(new ScentRevSearchResponse(List.of()));
        service.search("A_!%", 1);
        verify(store.perfumes).searchCached(eq("%a!_!!!%%"), any());
    }

    @Test void localMissMakesOneSmallSearchAndDeduplicatesFirstSeenSlugsWithoutImportOrPrefetch() {
        when(client.searchFragrances("Aventus", 2)).thenReturn(new ScentRevSearchResponse(List.of(
                remote("creed-aventus", "First"), remote("creed-aventus", "Duplicate"),
                remote("creed-aventus-cologne", "Second"), remote("creed-another", "Not returned"))));
        var result = service.search(" Aventus ", 2);
        assertThat(result.candidates()).extracting(PerfumeSearchCandidate::fragranceSlug)
                .containsExactly("creed-aventus", "creed-aventus-cologne");
        assertThat(result.candidates()).extracting(PerfumeSearchCandidate::name).containsExactly("First", "Second");
        assertThat(result.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.source()).isEqualTo(PerfumeSearchCandidate.Source.SCENTREV);
            assertThat(candidate.cached()).isFalse();
        });
        verify(client, times(1)).searchFragrances("Aventus", 2);
        verifyNoMoreInteractions(client); // Zero profiles, list_brands, filtered searches or pagination.
        verifyNoInteractions(store.mapper, store.brands);
        verify(store.perfumes, never()).save(any());
        assertThat(store.perfumeRows).isEmpty();
    }
    @Test void remoteCandidatesAreMarkedCachedWithOneBoundedRepositoryQuery() {
        when(client.searchFragrances("Aventus", 5)).thenReturn(new ScentRevSearchResponse(List.of(
                remote("creed-aventus", "Aventus"), remote("creed-aventus-cologne", "Cologne"))));
        when(store.perfumes.findCachedFragranceSlugs(anyCollection())).thenReturn(List.of("creed-aventus"));
        var result = service.search("Aventus", 5);
        assertThat(result.candidates()).extracting(PerfumeSearchCandidate::cached).containsExactly(true, false);
        assertThat(result.candidates()).extracting(PerfumeSearchCandidate::source)
                .containsOnly(PerfumeSearchCandidate.Source.SCENTREV);
        verify(store.perfumes).findCachedFragranceSlugs(argThat(slugs ->
                List.copyOf(slugs).equals(List.of("creed-aventus", "creed-aventus-cologne"))));
        verify(client).searchFragrances("Aventus", 5);
        verifyNoMoreInteractions(client);
        verifyNoInteractions(store.mapper);
    }
    @Test void noMatchesRemainEmptyWithoutExtraProviderOrCacheQueries() {
        when(client.searchFragrances("unknown", 10)).thenReturn(new ScentRevSearchResponse(List.of()));
        assertThat(service.search("unknown", 10).candidates()).isEmpty();
        verify(client).searchFragrances("unknown", 10);
        verifyNoMoreInteractions(client);
        verify(store.perfumes, never()).findCachedFragranceSlugs(anyCollection());
        verifyNoInteractions(store.mapper);
    }
    @Test void malformedCandidateOrMissingResponseSurfacesAsSafeSearchFailure() {
        when(client.searchFragrances("Aventus", 5)).thenReturn(new ScentRevSearchResponse(List.of(remote("bad/slug", "Bad"))), null);
        expect(PROVIDER_SEARCH_FAILURE, () -> service.search("Aventus", 5));
        expect(PROVIDER_SEARCH_FAILURE, () -> service.search("Aventus", 5));
        verify(client, times(2)).searchFragrances("Aventus", 5);
        verifyNoMoreInteractions(client);
        verifyNoInteractions(store.mapper);
    }
    @Test void searchProviderFailureIsNotSwallowedOrRetried() {
        when(client.searchFragrances(anyString(), anyInt())).thenThrow(privateFailure());
        expect(PROVIDER_SEARCH_FAILURE, () -> service.search("Aventus", 5));
        verify(client).searchFragrances("Aventus", 5);
        verifyNoMoreInteractions(client);
    }
    @Test void localDatabaseFailureDoesNotTriggerRemoteFallback() {
        when(store.perfumes.searchCached(anyString(), any())).thenThrow(privateFailure());
        expect(PERSISTENCE_FAILURE, () -> service.search("Aventus", 5));
        verifyNoInteractions(client);
    }

    @Test void cachedExactSlugReturnsExistingRowWithNoProviderCallsOrRefresh() {
        var existing = store.remember("creed-aventus", "id-aventus");
        assertThat(service.getOrImportBySlug(" CREED-AVENTUS ")).isSameAs(existing);
        assertThat(service.getOrImportBySlug("creed-aventus")).isSameAs(existing);
        verifyNoInteractions(client, store.mapper);
    }
    @Test void uncachedSelectionUsesOneProfileRealMapperAndReloadThenSecondCallHitsCache() {
        var profile = profile("creed-selected", "id-selected");
        when(client.getFragranceProfile("creed-selected")).thenReturn(profile);
        var first = service.getOrImportBySlug("creed-selected");
        var second = service.getOrImportBySlug("creed-selected");
        assertThat(first.getId()).isNotNull();
        assertThat(second).isSameAs(first);
        assertThat(store.perfumeRows).containsOnlyKeys("creed-selected");
        verify(client, times(1)).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
        verify(store.mapper, times(1)).importProfile(profile);
        verify(cache, times(3)).findCached("creed-selected"); // First lookup, committed reload, then cached second call.
        verify(store.perfumes, times(1)).save(any());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "bad/slug", "creed--aventus", "-creed", "creed-", "creed aventus"})
    void invalidSlugFailsBeforeIo(String slug) {
        expect(INVALID_SLUG, () -> service.getOrImportBySlug(slug));
        verifyNoInteractions(cache, client, store.mapper);
    }
    @Test void oversizedSlugIsRejected() {
        expect(INVALID_SLUG, () -> service.getOrImportBySlug("a".repeat(256)));
        verifyNoInteractions(client, cache);
    }
    @Test void absentIdentityWrongSlugInvalidBrandOrMissingPublicIdNeverReachesPersistence() {
        var missing = new ScentRevFragranceProfileResponse(null, null, null, null, null, null, null, null);
        var badBrand = new ScentRevFragranceProfileResponse(new ScentRevIdentity("Selected", "id-selected", "Creed", "bad/brand",
                "creed-selected", null, null, null, null, null), null, null, null, null, null, null, null);
        when(client.getFragranceProfile("creed-selected")).thenReturn(null, missing, profile("other-slug", "id-selected"),
                badBrand, profile("creed-selected", null));
        for (int i = 0; i < 5; i++) { expect(CANONICAL_IDENTITY_MISMATCH, () -> service.getOrImportBySlug("creed-selected")); }
        verifyNoInteractions(store.mapper);
        verify(cache, never()).importSelected(anyString(), any());
        assertThat(store.perfumeRows).isEmpty();
    }
    @Test void publicIdBelongingToAnotherCachedSlugIsRejectedBeforeMapper() {
        var unrelated = store.remember("other-fragrance", "id-selected");
        when(client.getFragranceProfile("creed-selected")).thenReturn(profile("creed-selected", "id-selected"));
        expect(IDENTIFIER_CONFLICT, () -> service.getOrImportBySlug("creed-selected"));
        verifyNoInteractions(store.mapper);
        assertThat(store.perfumeRows).containsOnlyKeys("other-fragrance");
        assertThat(store.perfumeRows.get("other-fragrance")).isSameAs(unrelated);
    }
    @Test void profileFailureAndImporterFailureSurfaceSafelyWithoutAdditionalCalls() {
        when(client.getFragranceProfile("creed-selected")).thenThrow(privateFailure());
        expect(PROFILE_LOOKUP_FAILURE, () -> service.getOrImportBySlug("creed-selected"));
        verifyNoInteractions(store.mapper);
        doReturn(profile("creed-selected", "id-selected")).when(client).getFragranceProfile("creed-selected");
        doThrow(privateFailure()).when(store.mapper).importProfile(any());
        expect(PERSISTENCE_FAILURE, () -> service.getOrImportBySlug("creed-selected"));
        verify(client, times(2)).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
    }
    @Test void authenticationAndMissingConfigurationHaveDistinctSafeCategories() {
        var authentication = mock(ScentRevMcpClientException.class);
        when(authentication.getFailureType()).thenReturn(ScentRevMcpClientException.FailureType.AUTHENTICATION);
        when(client.searchFragrances(anyString(), anyInt())).thenThrow(authentication);
        expect(PROVIDER_AUTHENTICATION, () -> service.search("Aventus", 5));
        var configuration = mock(ScentRevMcpClientException.class);
        when(configuration.getFailureType()).thenReturn(ScentRevMcpClientException.FailureType.CONFIGURATION);
        when(client.getFragranceProfile(anyString())).thenThrow(configuration);
        expect(PROVIDER_CONFIGURATION, () -> service.getOrImportBySlug("creed-selected"));
    }
    @Test void uniqueConstraintRaceReturnsMatchingCommittedWinnerWithoutAnotherProfile() {
        when(client.getFragranceProfile("creed-selected")).thenReturn(profile("creed-selected", "id-selected"));
        doAnswer(call -> {
            store.remember("creed-selected", "id-selected");
            throw new DataIntegrityViolationException("fake-private-sql-diagnostic");
        }).when(cache).importSelected(eq("creed-selected"), any());
        assertThat(service.getOrImportBySlug("creed-selected")).isSameAs(store.perfumeRows.get("creed-selected"));
        verify(client).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
    }
    @Test void conflictingRaceWinnerIsNeverReturned() {
        when(client.getFragranceProfile("creed-selected")).thenReturn(profile("creed-selected", "id-selected"));
        doAnswer(call -> {
            store.remember("creed-selected", "other-public-id");
            throw new DataIntegrityViolationException("fake-private-sql-diagnostic");
        }).when(cache).importSelected(eq("creed-selected"), any());
        expect(PERSISTENCE_FAILURE, () -> service.getOrImportBySlug("creed-selected"));
        verify(client).getFragranceProfile("creed-selected");
        verifyNoMoreInteractions(client);
    }
    @Test void missingCommittedReloadFailsInsteadOfReturningAnUnpersistedEntity() {
        when(client.getFragranceProfile("creed-selected")).thenReturn(profile("creed-selected", "id-selected"));
        doNothing().when(cache).importSelected(anyString(), any());
        expect(PERSISTENCE_FAILURE, () -> service.getOrImportBySlug("creed-selected"));
        verifyNoInteractions(store.mapper);
    }

    private static Fragrance remote(String slug, String name) { return new Fragrance(slug, "id-" + slug, name, "Creed", "creed"); }
    private static PerfumeSearchProjection localRow() {
        return new PerfumeSearchProjection() {
            public String getFragranceSlug() { return "creed-aventus"; }
            public String getPublicId() { return "id-aventus"; }
            public String getName() { return "Aventus"; }
            public String getBrandName() { return "Creed"; }
            public String getBrandSlug() { return "creed"; }
        };
    }
    private static RuntimeException privateFailure() { return new IllegalStateException("fake-private-secret", new RuntimeException("fake-provider-payload")); }
    private static void expect(ScentRevOnDemandException.FailureType type, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ScentRevOnDemandException.class).hasNoCause()
                .hasMessageNotContaining("fake-private-secret").hasMessageNotContaining("fake-provider-payload")
                .hasMessageNotContaining("fake-private-sql-diagnostic")
                .satisfies(error -> assertThat(((ScentRevOnDemandException) error).getFailureType()).isEqualTo(type));
    }
}
