package com.perfume.scentrev.progress;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CatalogImportBrandProgressRepository extends JpaRepository<CatalogImportBrandProgress, Long> {
    boolean existsByImportRun_IdAndBrandSlug(Long runId, String brandSlug);
    List<CatalogImportBrandProgress> findByImportRun_IdAndStatusOrderByCatalogPositionAsc(
            Long runId, CatalogImportBrandStatus status, Pageable page);
    List<CatalogImportBrandProgress> findByImportRun_IdAndStatusAndBrandSlugInOrderByCatalogPositionAsc(
            Long runId, CatalogImportBrandStatus status, Collection<String> slugs, Pageable page);
    List<CatalogImportBrandProgress> findByImportRun_IdAndStatusOrderByCatalogPositionAsc(
            Long runId, CatalogImportBrandStatus status);
    long countByImportRun_IdAndStatus(Long runId, CatalogImportBrandStatus status);
    @Query("select coalesce(sum(p.processedFragranceCount), 0) from CatalogImportBrandProgress p where p.importRun.id = :runId")
    long sumProcessedFragrances(@Param("runId") Long runId);
}
