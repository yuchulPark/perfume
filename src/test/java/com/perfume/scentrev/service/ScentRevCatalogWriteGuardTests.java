package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import com.perfume.ai.embedding.JdbcPerfumeEmbeddingStore;
import com.perfume.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class ScentRevCatalogWriteGuardTests {
    @Test void sharedNonblockingTransactionLockUsesTheExactEmbeddingSessionKey() throws Exception {
        var entityManager = mock(EntityManager.class); var query = mock(Query.class);
        when(entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock_shared(:key)")).thenReturn(query);
        when(query.setParameter("key", JdbcPerfumeEmbeddingStore.LOCK_ID)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(true);
        var guard = new ScentRevCatalogWriteGuard();
        ReflectionTestUtils.setField(guard, "entityManager", entityManager);
        guard.acquire();
        assertThat(ScentRevCatalogWriteGuard.class.getMethod("acquire").getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.MANDATORY);
        verify(query).setParameter("key", JdbcPerfumeEmbeddingStore.LOCK_ID);
        when(query.getSingleResult()).thenReturn(false);
        assertThatThrownBy(guard::acquire).hasMessage("ScentRev catalog write refused: EMBEDDING_BUSY").hasNoCause();
        when(query.getSingleResult()).thenThrow(new IllegalStateException("private-password"));
        assertThatThrownBy(guard::acquire).hasMessage("ScentRev catalog write refused: DATABASE").hasNoCause();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void bothPersistenceEntryPointsRefuseBeforeAnyCatalogAccessWhenEmbeddingHoldsTheLock(boolean page) {
        var brands = mock(BrandRepository.class); var perfumes = mock(PerfumeRepository.class);
        var perfumers = mock(PerfumerRepository.class); var links = mock(PerfumePerfumerRepository.class);
        var notes = mock(NoteRepository.class); var noteLinks = mock(PerfumeNoteRepository.class);
        var accords = mock(AccordRepository.class); var accordLinks = mock(PerfumeAccordRepository.class);
        var guard = mock(ScentRevCatalogWriteGuard.class);
        doThrow(new IllegalStateException("ScentRev catalog write refused: EMBEDDING_BUSY")).when(guard).acquire();
        var service = new ScentRevPhase1ImportService(brands, perfumes, perfumers, links, notes, noteLinks, accords, accordLinks);
        service.setWriteGuard(guard);
        assertThatThrownBy(() -> {
            if (page) { service.importPageForSeededBrand(1L, "creed", List.of(), true); }
            else { service.importProfile(null); }
        }).hasMessageContaining("EMBEDDING_BUSY");
        verifyNoInteractions(brands, perfumes, perfumers, links, notes, noteLinks, accords, accordLinks);
    }
}
