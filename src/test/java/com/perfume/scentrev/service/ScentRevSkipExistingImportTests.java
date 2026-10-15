package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.domain.Brand;
import com.perfume.repository.BrandRepository;
import com.perfume.repository.PerfumeImportIdentityProjection;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real orchestration and page lookup; repositories, persistence and ScentRev are mocked. */
class ScentRevSkipExistingImportTests {
    @ParameterizedTest
    @CsvSource({"10,10,0,1", "10,7,3,1", "70,60,10,7", "20,10,10,2", "10,0,10,1"})
    void onlyNewCandidatesFetchProfilesAndExistingOnlyPagesContinue(int total, int existing, int fresh, int pages) {
        var fixture = new Fixture(); fixture.catalog(total, existing);
        var result = fixture.service.importBrand(options(true));
        assertThat(result.completed()).isTrue();
        assertThat(result.skipExistingEnabled()).isTrue();
        assertThat(result.existingSkipped()).isEqualTo(existing);
        assertThat(result.newCandidates()).isEqualTo(fresh);
        assertThat(result.profilesFetched()).isEqualTo(fresh);
        assertThat(result.perfumesInserted()).isEqualTo(fresh);
        assertThat(result.perfumesUpdated()).isZero();
        assertThat(result.perfumesUnchanged()).isZero();
        assertThat(result.discoveryPages()).isEqualTo(pages);
        assertThat(result.rawResults()).isEqualTo(total);
        assertThat(result.uniqueFragrancesDiscovered()).isEqualTo(total);
        assertThat(result.totalMcpCalls()).isEqualTo(pages + fresh);
        assertThat(result.pagesCommitted()).isEqualTo((fresh + 9) / 10);
        for (int i = 0; i < pages; i++) { verify(fixture.client).searchFragrancesFiltered("creed", i * 10); }
        for (int i = existing; i < total; i++) { verify(fixture.client).getFragranceProfile("item-" + i); }
        verifyNoMoreInteractions(fixture.client); // Also proves zero wear/other calls for every skipped perfume.
        verify(fixture.perfumes, times(pages)).findImportIdentities(anyCollection(), anyCollection());
        verifyNoMoreInteractions(fixture.perfumes);
        if (fresh == 0) { verifyNoInteractions(fixture.persistence); }
    }

    @Test
    void explicitFalseAndTheDefaultNeverReadExistingIdentitiesAndKeepTheUpdatePath() {
        for (var options : List.of(new ScentRevSingleBrandImportOptions("creed", 100), options(false))) {
            var fixture = new Fixture(); fixture.catalog(2, 2);
            var result = fixture.service.importBrand(options);
            assertThat(result.skipExistingEnabled()).isFalse();
            assertThat(result.existingSkipped()).isZero();
            assertThat(result.newCandidates()).isNull();
            assertThat(result.profilesFetched()).isEqualTo(2);
            assertThat(result.totalMcpCalls()).isEqualTo(3);
            assertThat(result.perfumesUpdated()).isEqualTo(2);
            verifyNoInteractions(fixture.perfumes);
            verify(fixture.persistence).importPageForSeededBrand(eq(2L), eq("creed"), anyList());
            verify(fixture.persistence, never()).importPageForSeededBrand(anyLong(), anyString(), anyList(), anyBoolean());
        }
    }

    @Test
    void exactSlugWithoutPublicIdSafelySkipsAndRemembersThePersistedIdForDiscoveryDeduplication() {
        var fixture = new Fixture();
        fixture.identities.add(identity(1, "old", "known", 2, "creed"));
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, new Fragrance("old", null, "Name", null)));
        when(fixture.client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false,
                new Fragrance("alias", "known", "Different name", "creed")));
        var result = fixture.service.importBrand(options(true));
        assertThat(result.completed()).isTrue(); assertThat(result.existingSkipped()).isEqualTo(1);
        assertThat(result.duplicateDiscoveryCount()).isEqualTo(1); assertThat(result.profilesFetched()).isZero();
        assertThat(result.totalMcpCalls()).isEqualTo(2);
        verify(fixture.client).searchFragrancesFiltered("creed", 0); verify(fixture.client).searchFragrancesFiltered("creed", 10);
        verifyNoMoreInteractions(fixture.client); verifyNoInteractions(fixture.persistence);
        verify(fixture.perfumes).findImportIdentities(List.of("old"), List.of()); verifyNoMoreInteractions(fixture.perfumes);
    }

    @Test
    void discoveryDuplicatesAreRemovedBeforeTheSinglePageLookup() {
        var fixture = new Fixture(); fixture.catalog(1, 1);
        var item = new Fragrance("item-0", "id-item-0", "Any name", "creed");
        when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false, item, item));
        var result = fixture.service.importBrand(options(true));
        assertThat(result.existingSkipped()).isEqualTo(1); assertThat(result.duplicateDiscoveryCount()).isEqualTo(1);
        verify(fixture.perfumes).findImportIdentities(List.of("item-0"), List.of("id-item-0"));
        verifyNoMoreInteractions(fixture.perfumes); verifyNoInteractions(fixture.persistence);
    }

    @Test
    void foreignMatchesAreProfileCheckedAndCountedSeparatelyFromExistingSkips() {
        var fixture = new Fixture(); fixture.catalog(2, 1);
        fixture.identities.add(identity(9, "item-1", "id-item-1", 9, "foreign"));
        when(fixture.client.getFragranceProfile("item-1")).thenReturn(profile("item-1", "id-item-1", "foreign", false));
        var result = fixture.service.importBrand(options(true));
        assertThat(result.completed()).isTrue(); assertThat(result.existingSkipped()).isEqualTo(1);
        assertThat(result.skippedForeignBrand()).isEqualTo(1); assertThat(result.newCandidates()).isZero();
        assertThat(result.profilesFetched()).isEqualTo(1); assertThat(result.totalMcpCalls()).isEqualTo(2);
        verify(fixture.client).searchFragrancesFiltered("creed", 0); verify(fixture.client).getFragranceProfile("item-1");
        verifyNoMoreInteractions(fixture.client); verifyNoInteractions(fixture.persistence);
    }

    @Test
    void aNewForeignCandidateIsNotMistakenForASavedOrInsertedPerfume() {
        var fixture = new Fixture(); fixture.catalog(1, 0);
        when(fixture.client.getFragranceProfile("item-0")).thenReturn(profile("item-0", "id-item-0", "foreign", false));
        var result = fixture.service.importBrand(options(true));
        assertThat(result.newCandidates()).isEqualTo(1); assertThat(result.existingSkipped()).isZero();
        assertThat(result.skippedForeignBrand()).isEqualTo(1); assertThat(result.perfumesInserted()).isZero();
        verify(fixture.client, never()).getWearSummary(anyString()); verifyNoInteractions(fixture.persistence);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-public-id", "wrong-slug", "split-rows", "foreign-row", "conflicting-brand-hint"})
    void uncertainIdentityNeverSilentlySkipsAndRetainsTheProfileValidationPath(String kind) {
        var fixture = new Fixture(); fixture.catalog(1, 0);
        switch (kind) {
            case "wrong-public-id" -> fixture.identities.add(identity(1, "item-0", "different", 2, "creed"));
            case "wrong-slug" -> fixture.identities.add(identity(1, "different", "id-item-0", 2, "creed"));
            case "split-rows" -> {
                fixture.identities.add(identity(1, "item-0", "different", 2, "creed"));
                fixture.identities.add(identity(2, "different", "id-item-0", 2, "creed"));
            }
            case "foreign-row" -> fixture.identities.add(identity(1, "item-0", "id-item-0", 9, "foreign"));
            case "conflicting-brand-hint" -> {
                fixture.identities.add(identity(1, "item-0", "id-item-0", 2, "creed"));
                when(fixture.client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false,
                        new Fragrance("item-0", "id-item-0", "Name", "foreign")));
            }
            default -> throw new AssertionError();
        }
        // Existing per-profile identity validation must still reject a provider inconsistency before writes.
        when(fixture.client.getFragranceProfile("item-0")).thenReturn(profile("item-0", "provider-conflict", "creed", true));
        var failure = catchThrowableOfType(ScentRevSingleBrandImportException.class, () -> fixture.service.importBrand(options(true)));
        assertThat(failure.getResult().existingSkipped()).isZero();
        assertThat(failure.getResult().newCandidates()).isZero();
        assertThat(failure.getResult().completed()).isFalse();
        assertThat(failure).hasMessageContaining("identity does not match discovery");
        verify(fixture.client).getFragranceProfile("item-0"); verifyNoInteractions(fixture.persistence);
    }

    @Test
    void optionalWearIsFetchedOnlyForTheNewCandidate() {
        var fixture = new Fixture(); fixture.catalog(2, 1);
        when(fixture.client.getFragranceProfile("item-1")).thenReturn(profile("item-1", "id-item-1", "creed", false));
        when(fixture.client.getWearSummary("item-1")).thenReturn(new ObjectMapper().createObjectNode().put("public_id", "id-item-1"));
        var result = fixture.service.importBrand(options(true));
        assertThat(result.existingSkipped()).isEqualTo(1); assertThat(result.profilesFetched()).isEqualTo(1);
        assertThat(result.totalMcpCalls()).isEqualTo(3);
        verify(fixture.client).searchFragrancesFiltered("creed", 0); verify(fixture.client).getFragranceProfile("item-1");
        verify(fixture.client).getWearSummary("item-1"); verifyNoMoreInteractions(fixture.client);
    }

    @ParameterizedTest
    @EnumSource(value = ScentRevMcpClientException.FailureType.class, names = {"RATE_LIMIT", "SERVER_OUTPUT_SCHEMA", "TRANSPORT"})
    void profileFailurePreservesEarlierPagesAndStopsWithoutRetry(ScentRevMcpClientException.FailureType code) {
        var fixture = new Fixture(); fixture.catalog(20, 9);
        var error = mock(ScentRevMcpClientException.class); when(error.getFailureType()).thenReturn(code);
        when(fixture.client.getFragranceProfile("item-10")).thenThrow(error);
        var failure = catchThrowableOfType(ScentRevSingleBrandImportException.class, () -> fixture.service.importBrand(options(true)));
        var result = failure.getResult();
        assertThat(result.completed()).isFalse(); assertThat(result.existingSkipped()).isEqualTo(9);
        assertThat(result.perfumesInserted()).isEqualTo(1); assertThat(result.pagesCommitted()).isEqualTo(1);
        assertThat(result.newCandidates()).isEqualTo(11); assertThat(result.profilesFetched()).isEqualTo(1);
        assertThat(result.totalMcpCalls()).isEqualTo(4);
        verify(fixture.client).searchFragrancesFiltered("creed", 0); verify(fixture.client).getFragranceProfile("item-9");
        verify(fixture.client).searchFragrancesFiltered("creed", 10); verify(fixture.client).getFragranceProfile("item-10");
        verifyNoMoreInteractions(fixture.client);
        verify(fixture.persistence).importPageForSeededBrand(eq(2L), eq("creed"), anyList(), eq(true));
    }

    @Test
    void lookupFailureStopsBeforeProfileCallsAndDoesNotExposeDatabaseDiagnostics() {
        var fixture = new Fixture(); fixture.catalog(1, 0);
        when(fixture.perfumes.findImportIdentities(anyCollection(), anyCollection())).thenThrow(new IllegalStateException("private-sql-and-password"));
        var failure = catchThrowableOfType(ScentRevSingleBrandImportException.class, () -> fixture.service.importBrand(options(true)));
        assertThat(failure.getResult().stoppedStage()).isEqualTo("EXISTING_LOOKUP");
        assertThat(failure.getResult().totalMcpCalls()).isEqualTo(1);
        assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
        verify(fixture.client).searchFragrancesFiltered("creed", 0); verifyNoMoreInteractions(fixture.client);
        verifyNoInteractions(fixture.persistence);
    }

    static ScentRevSingleBrandImportOptions options(boolean skip) { return new ScentRevSingleBrandImportOptions("creed", 100, skip); }
    static ScentRevFilteredSearchResponse page(int offset, boolean more, Fragrance... rows) {
        return new ScentRevFilteredSearchResponse(List.of(rows), more, offset, 10, rows.length, false);
    }
    static ScentRevFragranceProfileResponse profile(String slug, String id, String brand, boolean wearPresent) {
        var value = new ScentRevFragranceProfileResponse(new ScentRevIdentity("Name " + slug, id, brand, brand, slug,
                null, null, null, null, null), null, List.of(), null, null, null, null, null);
        var raw = new ObjectMapper().createObjectNode();
        if (wearPresent) {
            raw.putObject("performance").putObject("season").put("score", 1);
            raw.withObject("/performance").putObject("time_of_day").put("score", 1);
        }
        return value.withRawResponse(raw);
    }
    static Identity identity(long id, String slug, String publicId, long brandId, String brandSlug) { return new Identity(id, publicId, slug, brandId, brandSlug); }
    record Identity(Long id, String publicId, String fragranceSlug, Long brandId, String brandSlug) implements PerfumeImportIdentityProjection {
        public Long getId() { return id; } public String getPublicId() { return publicId; }
        public String getFragranceSlug() { return fragranceSlug; } public Long getBrandId() { return brandId; }
        public String getBrandSlug() { return brandSlug; }
    }
    static class Fixture {
        final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
        final BrandRepository brands = mock(BrandRepository.class);
        final PerfumeRepository perfumes = mock(PerfumeRepository.class);
        final ScentRevPhase1ImportService persistence = mock(ScentRevPhase1ImportService.class);
        final List<PerfumeImportIdentityProjection> identities = new ArrayList<>();
        final Brand brand = new Brand("Creed", "creed");
        final ScentRevExistingPerfumeLookup lookup = new ScentRevExistingPerfumeLookup(perfumes);
        final ScentRevSingleBrandImportService service;
        Fixture() {
            ReflectionTestUtils.setField(brand, "id", 2L);
            when(brands.findByBrandSlug("creed")).thenReturn(Optional.of(brand));
            when(perfumes.findImportIdentities(anyCollection(), anyCollection())).thenAnswer(call -> {
                Collection<String> slugs = call.getArgument(0), ids = call.getArgument(1);
                return identities.stream().filter(row -> slugs.contains(row.getFragranceSlug()) || ids.contains(row.getPublicId())).toList();
            });
            when(client.getFragranceProfile(anyString())).thenAnswer(call -> profile(call.getArgument(0), "id-" + call.getArgument(0), "creed", true));
            when(persistence.importPageForSeededBrand(anyLong(), anyString(), anyList(), eq(true))).thenAnswer(call -> counts(call.<List<?>>getArgument(2).size(), 0));
            when(persistence.importPageForSeededBrand(anyLong(), anyString(), anyList())).thenAnswer(call -> counts(0, call.<List<?>>getArgument(2).size()));
            service = new ScentRevSingleBrandImportService(brands, new ScentRevCuratedBrandConfiguration(new ObjectMapper()), client, persistence, lookup);
        }
        void catalog(int total, int existing) {
            for (int i = 0; i < existing; i++) { identities.add(identity(i + 1, "item-" + i, "id-item-" + i, 2, "creed")); }
            when(client.searchFragrancesFiltered(eq("creed"), anyInt())).thenAnswer(call -> {
                int offset = call.getArgument(1);
                var rows = IntStream.range(offset, Math.min(offset + 10, total))
                        .mapToObj(i -> new Fragrance("item-" + i, "id-item-" + i, "Name", "creed")).toArray(Fragrance[]::new);
                return page(offset, offset + rows.length < total, rows);
            });
        }
        static ScentRevPhase1PageImportResult counts(int inserted, int updated) {
            return new ScentRevPhase1PageImportResult(inserted, updated, 0, Set.of(), Set.of(), Set.of(), 0, 0, 0);
        }
    }
}
