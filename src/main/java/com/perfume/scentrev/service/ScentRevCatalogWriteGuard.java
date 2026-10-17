package com.perfume.scentrev.service;

import com.perfume.ai.embedding.JdbcPerfumeEmbeddingStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cooperating import transactions share the existing embedding batch's database-wide lock key. */
@Component
public class ScentRevCatalogWriteGuard {
    @PersistenceContext private EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire() {
        Object acquired;
        try {
            acquired = entityManager.createNativeQuery("SELECT pg_try_advisory_xact_lock_shared(:key)")
                    .setParameter("key", JdbcPerfumeEmbeddingStore.LOCK_ID).getSingleResult();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("ScentRev catalog write refused: DATABASE");
        }
        if (!Boolean.TRUE.equals(acquired)) {
            throw new IllegalStateException("ScentRev catalog write refused: EMBEDDING_BUSY");
        }
    }
}
