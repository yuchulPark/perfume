package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.perfume.api.service.PerfumeQueryService;
import com.perfume.repository.*;

class CatalogSearchRoutingTests {
    private final PerfumeRepository repository = mock(PerfumeRepository.class);
    private final CatalogSearch search = mock(CatalogSearch.class);
    private final PerfumeQueryService service = new PerfumeQueryService(repository, mock(PerfumeNoteRepository.class),
            mock(PerfumeUnlayeredNoteRepository.class), mock(PerfumeAccordRepository.class), mock(PerfumePerfumerRepository.class),
            mock(PerfumeMetricRepository.class), mock(PerfumeOpinionRepository.class), mock(PerfumeSimilarityRepository.class), search);

    @Test void hydrationUsesCurrentPostgresDataInRelevanceOrderWithExactPageMetadata() {
        when(search.search(1, 2, "rose", "creed")).thenReturn(Optional.of(new CatalogSearch.Hits(List.of(9L, 2L), 5)));
        var rows = List.of(row(2, "Current two"), row(9, "Current nine"));
        when(repository.findCatalogByIds(List.of(9L, 2L), "creed")).thenReturn(rows);
        var result = service.list(1, 2, "rose", "creed");
        assertThat(result.content()).extracting("id").containsExactly(9L, 2L);
        assertThat(result.content()).extracting("name").containsExactly("Current nine", "Current two");
        assertThat(result.totalElements()).isEqualTo(5); assertThat(result.totalPages()).isEqualTo(3);
        assertThat(result.page()).isEqualTo(1); assertThat(result.first()).isFalse(); assertThat(result.last()).isFalse();
        verify(repository, never()).findCatalogPage(any(), any(), any());
    }
    @Test void zeroHitsAreNotReplacedWithDifferentSqlMatches() {
        when(search.search(0, 20, "rose", null)).thenReturn(Optional.of(new CatalogSearch.Hits(List.of(), 0)));
        var result = service.list(0, 20, "rose", null);
        assertThat(result.totalElements()).isZero(); assertThat(result.first()).isTrue(); assertThat(result.last()).isTrue();
        verifyNoInteractions(repository);
    }
    @Test void disabledOrFailedSearchPreservesLikeEscapingAndBrandPage() {
        when(search.search(2, 7, " A%B_C! ", "creed")).thenReturn(Optional.empty());
        when(repository.findCatalogPage("%a!%b!_c!!%", "creed", PageRequest.of(2, 7)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 7), 0));
        assertThat(service.list(2, 7, " A%B_C! ", "creed").content()).isEmpty();
        verify(repository).findCatalogPage("%a!%b!_c!!%", "creed", PageRequest.of(2, 7));
    }
    @Test void deletedRowsOrChangedBrandsFallBackAsAWholePage() {
        when(search.search(0, 20, "rose", "creed")).thenReturn(Optional.of(new CatalogSearch.Hits(List.of(9L), 1)));
        when(repository.findCatalogByIds(List.of(9L), "creed")).thenReturn(List.of());
        var sqlRow = row(2, "Rose");
        when(repository.findCatalogPage("%rose%", "creed", PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(sqlRow)));
        assertThat(service.list(0, 20, "rose", "creed").content()).extracting("id").containsExactly(2L);
    }
    @Test void plainCatalogAndHealthChecksNeverUseTheIndex() {
        when(repository.findCatalogPage(null, null, PageRequest.of(0, 1))).thenReturn(new PageImpl<>(List.of()));
        service.list(0, 1, null, null); verifyNoInteractions(search);
    }
    private PerfumeListProjection row(long id, String name) {
        var row = mock(PerfumeListProjection.class); when(row.getId()).thenReturn(id); when(row.getName()).thenReturn(name); return row;
    }
}
