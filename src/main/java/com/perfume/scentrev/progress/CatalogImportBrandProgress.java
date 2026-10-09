package com.perfume.scentrev.progress;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One immutable planned canonical brand plus latest-attempt operational progress. */
@Entity
@Table(name = "catalog_import_brand_progress", uniqueConstraints = {
        @UniqueConstraint(name = "uk_catalog_progress_run_brand", columnNames = {"run_id", "brand_slug"}),
        @UniqueConstraint(name = "uk_catalog_progress_run_position", columnNames = {"run_id", "catalog_position"})
}, indexes = @Index(name = "idx_catalog_progress_run_status_position", columnList = "run_id,status,catalog_position"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CatalogImportBrandProgress {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, foreignKey = @ForeignKey(name = "fk_catalog_progress_run"))
    private CatalogImportRun importRun;
    @Column(name = "brand_slug", nullable = false, length = 255)
    private String brandSlug;
    @Column(name = "brand_name", columnDefinition = "text")
    private String brandName;
    @Column(name = "reported_fragrance_count")
    private Long reportedFragranceCount;
    @Column(name = "catalog_position", nullable = false)
    private Integer catalogPosition;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CatalogImportBrandStatus status;
    @Column(name = "processed_fragrance_count", nullable = false)
    private Long processedFragranceCount;
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;
    @Column(name = "failure_message", length = 512)
    private String failureMessage;
    @Column(name = "started_at", columnDefinition = "timestamp with time zone")
    private Instant startedAt;
    @Column(name = "finished_at", columnDefinition = "timestamp with time zone")
    private Instant finishedAt;
    @Column(name = "last_updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    private Instant lastUpdatedAt;

    public CatalogImportBrandProgress(CatalogImportRun run, String slug, String name, Long reported, int position, Instant now) {
        importRun = run;
        brandSlug = slug;
        brandName = name;
        reportedFragranceCount = reported;
        catalogPosition = position;
        status = CatalogImportBrandStatus.PENDING;
        processedFragranceCount = 0L;
        attemptCount = 0;
        lastUpdatedAt = now;
    }

    public void start(Instant now) {
        if (status != CatalogImportBrandStatus.PENDING) { throw new IllegalStateException("Only a pending brand can be claimed."); }
        status = CatalogImportBrandStatus.RUNNING;
        attemptCount = Math.addExact(attemptCount, 1);
        processedFragranceCount = 0L;
        failureMessage = null;
        startedAt = now;
        finishedAt = null;
        lastUpdatedAt = now;
    }

    public void complete(long processed, Instant now) { finish(CatalogImportBrandStatus.COMPLETED, processed, null, now); }
    public void fail(long processed, String safeMessage, Instant now) { finish(CatalogImportBrandStatus.FAILED, processed, safeMessage, now); }

    private void finish(CatalogImportBrandStatus outcome, long processed, String message, Instant now) {
        if (status != CatalogImportBrandStatus.RUNNING || processed < 0 || (message != null && message.length() > 512)) {
            throw new IllegalStateException("Invalid brand progress transition.");
        }
        status = outcome;
        processedFragranceCount = processed;
        failureMessage = message;
        finishedAt = now;
        lastUpdatedAt = now;
    }

    public void requeue(Instant now) {
        if (status != CatalogImportBrandStatus.RUNNING && status != CatalogImportBrandStatus.FAILED) {
            throw new IllegalStateException("Only interrupted or failed work can be requeued.");
        }
        status = CatalogImportBrandStatus.PENDING;
        processedFragranceCount = 0L;
        failureMessage = null;
        startedAt = null;
        finishedAt = null;
        lastUpdatedAt = now;
    }
}
