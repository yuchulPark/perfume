package com.perfume.scentrev.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import com.perfume.scentrev.progress.*;

/** Repository boundaries backed by test-owned memory; no database, application context or network. */
class CatalogBatchTestStore {
    final CatalogImportRunRepository runs = mock(CatalogImportRunRepository.class);
    final CatalogImportBrandProgressRepository brands = mock(CatalogImportBrandProgressRepository.class);
    final Map<Long, CatalogImportRun> runRows = new LinkedHashMap<>();
    final Map<Long, CatalogImportBrandProgress> brandRows = new LinkedHashMap<>();
    Runnable databaseCheck = () -> { };
    private long nextRun;
    private long nextBrand;

    CatalogBatchTestStore() {
        when(runs.save(any())).thenAnswer(call -> {
            databaseCheck.run();
            CatalogImportRun run = call.getArgument(0);
            if (run.getId() == null) { ReflectionTestUtils.setField(run, "id", ++nextRun); }
            runRows.put(run.getId(), run);
            return run;
        });
        when(runs.lockById(anyLong())).thenAnswer(call -> {
            databaseCheck.run();
            return Optional.ofNullable(runRows.get(call.getArgument(0)));
        });
        when(brands.saveAndFlush(any())).thenAnswer(call -> saveBrand(call.getArgument(0)));
        when(brands.saveAllAndFlush(any())).thenAnswer(call -> {
            var saved = new ArrayList<CatalogImportBrandProgress>();
            Iterable<CatalogImportBrandProgress> rows = call.getArgument(0);
            rows.forEach(row -> saved.add(saveBrand(row)));
            databaseCheck.run();
            return saved;
        });
        when(brands.findById(anyLong())).thenAnswer(call -> {
            databaseCheck.run();
            return Optional.ofNullable(brandRows.get(call.getArgument(0)));
        });
        when(brands.countByImportRun_IdAndStatus(anyLong(), any())).thenAnswer(call ->
                (long) eligible(call.getArgument(0), call.getArgument(1)).size());
        when(brands.sumProcessedFragrances(anyLong())).thenAnswer(call -> {
            databaseCheck.run();
            Long runId = call.getArgument(0);
            return brandRows.values().stream().filter(row -> runId.equals(row.getImportRun().getId()))
                    .mapToLong(CatalogImportBrandProgress::getProcessedFragranceCount).sum();
        });
        when(brands.existsByImportRun_IdAndBrandSlug(anyLong(), anyString())).thenAnswer(call -> {
            databaseCheck.run();
            Long runId = call.getArgument(0);
            String slug = call.getArgument(1);
            return brandRows.values().stream().anyMatch(row -> runId.equals(row.getImportRun().getId()) && slug.equals(row.getBrandSlug()));
        });
        when(brands.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(anyLong(), any(), any(Pageable.class)))
                .thenAnswer(call -> eligible(call.getArgument(0), call.getArgument(1)).stream()
                        .limit(((Pageable) call.getArgument(2)).getPageSize()).toList());
        when(brands.findByImportRun_IdAndStatusOrderByCatalogPositionAsc(anyLong(), any()))
                .thenAnswer(call -> eligible(call.getArgument(0), call.getArgument(1)));
        when(brands.findByImportRun_IdAndStatusAndBrandSlugInOrderByCatalogPositionAsc(anyLong(), any(), anyCollection(), any(Pageable.class)))
                .thenAnswer(call -> {
                    java.util.Collection<String> slugs = call.getArgument(2);
                    return eligible(call.getArgument(0), call.getArgument(1)).stream().filter(row -> slugs.contains(row.getBrandSlug()))
                            .limit(((Pageable) call.getArgument(3)).getPageSize()).toList();
                });
    }

    private CatalogImportBrandProgress saveBrand(CatalogImportBrandProgress row) {
        databaseCheck.run();
        if (row.getId() == null) { ReflectionTestUtils.setField(row, "id", ++nextBrand); }
        brandRows.put(row.getId(), row);
        return row;
    }

    List<CatalogImportBrandProgress> eligible(Long runId, CatalogImportBrandStatus status) {
        databaseCheck.run();
        return brandRows.values().stream().filter(row -> runId.equals(row.getImportRun().getId()) && row.getStatus() == status)
                .sorted(Comparator.comparingInt(CatalogImportBrandProgress::getCatalogPosition)).toList();
    }
}
