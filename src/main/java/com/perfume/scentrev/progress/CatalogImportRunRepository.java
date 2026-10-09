package com.perfume.scentrev.progress;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogImportRunRepository extends JpaRepository<CatalogImportRun, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CatalogImportRun r where r.id = :runId")
    Optional<CatalogImportRun> lockById(@Param("runId") Long runId);
}
