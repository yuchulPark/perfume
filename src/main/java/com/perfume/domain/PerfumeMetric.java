package com.perfume.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One provider metric per stable JSON field path; scores are never rescaled or rounded. */
@Entity
@Table(name = "perfume_metrics", uniqueConstraints = @UniqueConstraint(name = "uk_perfume_metrics_key", columnNames = {"perfume_id", "metric_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeMetric {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "perfume_id", nullable = false) private Perfume perfume;
    @Column(name = "metric_key", nullable = false, length = 255) private String metricKey;
    @Column(columnDefinition = "numeric") private BigDecimal score;
    @Column(columnDefinition = "text") private String category;
    @Column(name = "n_records") private Long nRecords;
    @Column(columnDefinition = "text") private String reliability;
    @Column(columnDefinition = "text") private String scale;
    @Column(name = "derived_from", columnDefinition = "text") private String derivedFrom;
    @Column(name = "reviews_count") private Long reviewsCount;
    @Column(columnDefinition = "text") private String label;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private JsonNode details;

    public PerfumeMetric(Perfume perfume, String metricKey) { this.perfume = perfume; this.metricKey = metricKey; }
    public void update(BigDecimal score, String category, Long nRecords, String reliability, String scale,
            String derivedFrom, Long reviewsCount, String label, JsonNode details) {
        this.score = score; this.category = category; this.nRecords = nRecords; this.reliability = reliability;
        this.scale = scale; this.derivedFrom = derivedFrom; this.reviewsCount = reviewsCount; this.label = label;
        this.details = details.deepCopy();
    }
}
