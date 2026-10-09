package com.perfume.scentrev.progress;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Operational execution state only; no perfume data or provider payloads. */
@Entity
@Table(name = "catalog_import_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogImportRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CatalogImportRunStatus status;
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant createdAt;
    @Column(name = "started_at", columnDefinition = "timestamp with time zone")
    private Instant startedAt;
    @Column(name = "finished_at", columnDefinition = "timestamp with time zone")
    private Instant finishedAt;
    @Column(name = "last_updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant lastUpdatedAt;
    @Column(name = "total_brands", nullable = false)
    private Integer totalBrands;
    @Column(name = "total_reported_fragrances", nullable = false)
    private Long totalReportedFragrances;
    @Column(name = "brands_with_unknown_count", nullable = false)
    private Integer brandsWithUnknownCount;
    @Column(name = "brands_completed", nullable = false)
    private Integer brandsCompleted;
    @Column(name = "brands_failed", nullable = false)
    private Integer brandsFailed;
    @Column(name = "active_batch_token", length = 36)
    private String activeBatchToken;

    public CatalogImportRun(int total, long reported, int unknown, Instant now) {
        if (total < 0 || reported < 0 || unknown < 0 || unknown > total) {
            throw new IllegalArgumentException("Invalid catalog snapshot totals.");
        }
        totalBrands = total;
        totalReportedFragrances = reported;
        brandsWithUnknownCount = unknown;
        brandsCompleted = 0;
        brandsFailed = 0;
        createdAt = now;
        lastUpdatedAt = now;
        status = total == 0 ? CatalogImportRunStatus.COMPLETED : CatalogImportRunStatus.PLANNED;
        finishedAt = total == 0 ? now : null;
    }

    public void beginBatch(String token, Instant now) {
        if (activeBatchToken != null) {
            throw new IllegalStateException("Catalog run already has an active batch; explicit recovery is required after interruption.");
        }
        activeBatchToken = token;
        if (startedAt == null) { startedAt = now; }
        status = CatalogImportRunStatus.RUNNING;
        finishedAt = null;
        lastUpdatedAt = now;
    }

    public void releaseBatch(Instant now) {
        activeBatchToken = null;
        lastUpdatedAt = now;
    }

    public void updateTotals(int completed, int failed, int pending, int running, Instant now) {
        brandsCompleted = completed;
        brandsFailed = failed;
        lastUpdatedAt = now;
        if (pending == 0 && running == 0 && activeBatchToken == null) {
            status = failed == 0 ? CatalogImportRunStatus.COMPLETED : CatalogImportRunStatus.COMPLETED_WITH_FAILURES;
            if (finishedAt == null) { finishedAt = now; }
        } else {
            status = startedAt == null && activeBatchToken == null ? CatalogImportRunStatus.PLANNED : CatalogImportRunStatus.RUNNING;
            finishedAt = null;
        }
    }
}
