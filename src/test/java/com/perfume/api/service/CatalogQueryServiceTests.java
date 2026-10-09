package com.perfume.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfume.domain.*;
import com.perfume.repository.*;

/** Real DTO mapping with mocked repository boundaries; never initializes JDBC or a provider. */
class CatalogQueryServiceTests {
    @Test
    void brandsAreMappedToDtosIncludingAnEmptyCatalog() {
        var repository = mock(BrandRepository.class); var service = new BrandQueryService(repository);
        var brand = identified(new Brand("Kierin NYC", "kierin"), 1L);
        when(repository.findAllByOrderByNameAscIdAsc()).thenReturn(List.of(brand), List.of());
        assertThat(service.list()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(1L); assertThat(row.name()).isEqualTo("Kierin NYC");
            assertThat(row.brandSlug()).isEqualTo("kierin");
        });
        assertThat(service.list()).isEmpty(); verify(repository, times(2)).findAllByOrderByNameAscIdAsc();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void scalarPageMapsNullableFieldsAndExactPaginationWithoutChildQueries() {
        var fixture = new Fixture(); var projection = mock(PerfumeListProjection.class);
        when(projection.getId()).thenReturn(4L); when(projection.getName()).thenReturn("Aventus");
        when(projection.getReleaseYear()).thenReturn(null);
        when(projection.getPublicId()).thenReturn("provider-id"); when(projection.getFragranceSlug()).thenReturn("creed-aventus");
        when(projection.getBrandId()).thenReturn(1L); when(projection.getBrandName()).thenReturn("Creed"); when(projection.getBrandSlug()).thenReturn("creed");
        when(fixture.perfumes.findCatalogPage(null, null, PageRequest.of(1, 20)))
                .thenReturn(new PageImpl<>(List.of(projection), PageRequest.of(1, 20), 57));
        var page = fixture.service.list(1, 20, null, null);
        assertThat(page.page()).isEqualTo(1); assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(57); assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.first()).isFalse(); assertThat(page.last()).isFalse();
        assertThat(page.content()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(4L); assertThat(row.brand().name()).isEqualTo("Creed");
            assertThat(row.releaseYear()).isNull(); assertThat(row.imageUrl()).isNull();
        });
        verifyNoInteractions(fixture.notes, fixture.flatNotes, fixture.accords, fixture.perfumers, fixture.metrics, fixture.opinions, fixture.similarities);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void blankSearchUsesAnUnfilteredDatabasePage(String query) {
        var fixture = new Fixture();
        when(fixture.perfumes.findCatalogPage(null, "kierin", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        var page = fixture.service.list(0, 20, query, "kierin");
        assertThat(page.content()).isEmpty(); assertThat(page.totalElements()).isZero();
        verify(fixture.perfumes).findCatalogPage(null, "kierin", PageRequest.of(0, 20));
    }

    @Test
    void searchEscapesWildcardsAndPassesFilteringAndPagingToTheDatabase() {
        var fixture = new Fixture();
        when(fixture.perfumes.findCatalogPage("%a!%b!_c!!%", "kierin", PageRequest.of(3, 7)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(3, 7), 14));
        var page = fixture.service.list(3, 7, "  A%B_C!  ", "kierin");
        assertThat(page.content()).isEmpty(); assertThat(page.page()).isEqualTo(3); assertThat(page.totalElements()).isEqualTo(14);
        verify(fixture.perfumes).findCatalogPage("%a!%b!_c!!%", "kierin", PageRequest.of(3, 7));
        verifyNoMoreInteractions(fixture.perfumes);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void invalidPageRequestsReachNoRepository(int page, int size) {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.service.list(page, size, null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(fixture.perfumes);
    }

    @Test
    void detailReturnsEveryNormalizedSectionWithPreciseMetricsAndOneReadPerRelationship() {
        var fixture = new Fixture(); var perfume = fixture.perfume;
        perfume.updateScentRevDetails("creed-aventus", "Aventus", 2010, "Stored description", 123L);
        perfume.updateImageUrl("https://example.invalid/stored.jpg");
        var note = identified(new Note("Musk"), 5L);
        when(fixture.notes.findDetailNotes(2L)).thenReturn(List.of(new PerfumeNote(perfume, note, NoteLayer.TOP, 0),
                new PerfumeNote(perfume, note, NoteLayer.MIDDLE, 0), new PerfumeNote(perfume, note, NoteLayer.BASE, 0)));
        when(fixture.flatNotes.findDetailNotes(2L)).thenReturn(List.of(new PerfumeUnlayeredNote(perfume, note, 4)));
        var accord = identified(new Accord("woody"), 6L);
        when(fixture.accords.findDetailAccords(2L)).thenReturn(List.of(new PerfumeAccord(perfume, accord, null, new BigDecimal("0.81234567890123456789"), 0)));
        var perfumer = identified(new Perfumer("perfumer-id", "Stored perfumer", null, null, null), 7L);
        when(fixture.perfumers.findDetailPerfumers(2L)).thenReturn(List.of(new PerfumePerfumer(perfume, perfumer)));
        var metricRows = List.of("identity.rating", "identity.gender", "performance.longevity", "performance.sillage",
                "performance.projection", "performance.season.by_season.spring", "performance.time_of_day", "appreciation", "price_value")
                .stream().map(key -> {
                    var row = new PerfumeMetric(perfume, key);
                    row.update(new BigDecimal("0.91234567890123456789"), "stored-category", 4294967297L, null, null, null, null, null,
                            new ObjectMapper().createObjectNode().put("provider_extra", "preserved"));
                    return row;
                }).toList();
        when(fixture.metrics.findByPerfume_IdOrderByMetricKeyAsc(2L)).thenReturn(metricRows);
        var pro = new PerfumeOpinion(perfume, PerfumeOpinion.Kind.PRO, "pro-hash", "Stored pro");
        pro.update(null, 0, null, null, new ObjectMapper().createObjectNode());
        var con = new PerfumeOpinion(perfume, PerfumeOpinion.Kind.CON, "con-hash", "Stored con");
        con.update("community", 0, null, null, new ObjectMapper().createObjectNode());
        when(fixture.opinions.findByPerfume_IdOrderByPositionAscIdAsc(2L)).thenReturn(List.of(pro, con));
        var similar = new PerfumeSimilarity(perfume, "slug:unimported-fragrance");
        similar.update("slug:unimported-fragrance", null, "unimported-fragrance", "Unimported fragrance", null, null, 0, new ObjectMapper().createObjectNode());
        when(fixture.similarities.findByPerfume_IdOrderByPositionAscIdAsc(2L)).thenReturn(List.of(similar));
        var response = fixture.service.detail(2L);
        assertThat(response.description()).isEqualTo("Stored description"); assertThat(response.releaseYear()).isEqualTo(2010);
        assertThat(response.reviewsCount()).isEqualTo(123L); assertThat(response.imageUrl()).isEqualTo("https://example.invalid/stored.jpg");
        assertThat(response.notes().top()).hasSize(1); assertThat(response.notes().middle()).hasSize(1);
        assertThat(response.notes().base()).hasSize(1); assertThat(response.notes().unlayered()).singleElement().satisfies(row -> assertThat(row.position()).isEqualTo(4));
        assertThat(response.accords()).singleElement().satisfies(row -> {
            assertThat(row.percentage()).isNull(); assertThat(row.score()).isEqualByComparingTo("0.81234567890123456789");
        });
        assertThat(response.perfumers()).singleElement().satisfies(row -> { assertThat(row.company()).isNull(); assertThat(row.name()).isEqualTo("Stored perfumer"); });
        assertThat(response.metrics()).hasSize(9);
        assertThat(response.metrics().get("identity.rating").score()).isEqualByComparingTo("0.91234567890123456789");
        assertThat(response.metrics().get("identity.rating").nRecords()).isEqualTo(4294967297L);
        assertThat(response.pros()).singleElement().satisfies(row -> assertThat(row.text()).isEqualTo("Stored pro"));
        assertThat(response.cons()).singleElement().satisfies(row -> assertThat(row.text()).isEqualTo("Stored con"));
        assertThat(response.similarFragrances()).singleElement().satisfies(row -> { assertThat(row.publicId()).isNull(); assertThat(row.fragranceSlug()).isEqualTo("unimported-fragrance"); });
        ((ObjectNode) response.metrics().get("identity.rating").details()).put("provider_extra", "changed by caller");
        assertThat(metricRows.get(0).getDetails().path("provider_extra").asText()).isEqualTo("preserved");
        fixture.verifySingleReads();
    }

    @Test
    void sparseDetailReturnsNullScalarsAndEmptyCollectionsNormally() {
        var fixture = new Fixture(); var response = fixture.service.detail(2L);
        assertThat(response.description()).isNull(); assertThat(response.releaseYear()).isNull();
        assertThat(response.imageUrl()).isNull(); assertThat(response.reviewsCount()).isNull();
        assertThat(response.notes().top()).isEmpty(); assertThat(response.notes().middle()).isEmpty();
        assertThat(response.notes().base()).isEmpty(); assertThat(response.notes().unlayered()).isEmpty();
        assertThat(response.accords()).isEmpty(); assertThat(response.perfumers()).isEmpty(); assertThat(response.metrics()).isEmpty();
        assertThat(response.pros()).isEmpty(); assertThat(response.cons()).isEmpty(); assertThat(response.similarFragrances()).isEmpty();
        fixture.verifySingleReads();
    }

    @Test
    void manyChildrenStillUseOneBulkRepositoryReadPerRelationship() {
        var fixture = new Fixture();
        when(fixture.notes.findDetailNotes(2L)).thenReturn(IntStream.range(0, 100)
                .mapToObj(i -> new PerfumeNote(fixture.perfume, identified(new Note("Note " + i), 10L + i), NoteLayer.BASE, i)).toList());
        assertThat(fixture.service.detail(2L).notes().base()).hasSize(100);
        fixture.verifySingleReads();
    }

    @Test
    void unknownPerfumeStopsBeforeAnyChildReads() {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.service.detail(999L)).isInstanceOf(PerfumeNotFoundException.class);
        verifyNoInteractions(fixture.notes, fixture.flatNotes, fixture.accords, fixture.perfumers, fixture.metrics, fixture.opinions, fixture.similarities);
    }

    private static <T> T identified(T entity, long id) { ReflectionTestUtils.setField(entity, "id", id); return entity; }
    private static class Fixture {
        final PerfumeRepository perfumes = mock(PerfumeRepository.class);
        final PerfumeNoteRepository notes = mock(PerfumeNoteRepository.class);
        final PerfumeUnlayeredNoteRepository flatNotes = mock(PerfumeUnlayeredNoteRepository.class);
        final PerfumeAccordRepository accords = mock(PerfumeAccordRepository.class);
        final PerfumePerfumerRepository perfumers = mock(PerfumePerfumerRepository.class);
        final PerfumeMetricRepository metrics = mock(PerfumeMetricRepository.class);
        final PerfumeOpinionRepository opinions = mock(PerfumeOpinionRepository.class);
        final PerfumeSimilarityRepository similarities = mock(PerfumeSimilarityRepository.class);
        final PerfumeQueryService service = new PerfumeQueryService(perfumes, notes, flatNotes, accords, perfumers, metrics, opinions, similarities);
        final Perfume perfume = identified(new Perfume("provider-id", "creed-aventus", "Aventus", null, null, null,
                identified(new Brand("Creed", "creed"), 1L)), 2L);
        Fixture() { when(perfumes.findDetailById(2L)).thenReturn(Optional.of(perfume)); }
        void verifySingleReads() {
            verify(perfumes).findDetailById(2L); verify(notes).findDetailNotes(2L); verify(flatNotes).findDetailNotes(2L);
            verify(accords).findDetailAccords(2L); verify(perfumers).findDetailPerfumers(2L);
            verify(metrics).findByPerfume_IdOrderByMetricKeyAsc(2L); verify(opinions).findByPerfume_IdOrderByPositionAscIdAsc(2L);
            verify(similarities).findByPerfume_IdOrderByPositionAscIdAsc(2L);
            verifyNoMoreInteractions(perfumes, notes, flatNotes, accords, perfumers, metrics, opinions, similarities);
        }
    }
}
