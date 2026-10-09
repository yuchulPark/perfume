package com.perfume.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** References do not create/import their target perfumes or brands. */
@Entity
@Table(name = "perfume_similarities", uniqueConstraints = @UniqueConstraint(name = "uk_perfume_similarities_target", columnNames = {"perfume_id", "reference_key"}),
        indexes = @Index(name = "idx_perfume_similarities_target_slug", columnList = "related_fragrance_slug"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeSimilarity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "perfume_id", nullable = false) private Perfume perfume;
    @Column(name = "reference_key", nullable = false, length = 384) private String referenceKey;
    @Column(name = "related_public_id", length = 128) private String relatedPublicId;
    @Column(name = "related_fragrance_slug", length = 255) private String relatedFragranceSlug;
    @Column(name = "related_name", columnDefinition = "text") private String relatedName;
    @Column(name = "like_ratio", columnDefinition = "numeric") private BigDecimal likeRatio;
    @Column(name = "n_records") private Long nRecords;
    private Integer position;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private JsonNode details;

    public PerfumeSimilarity(Perfume perfume, String referenceKey) { this.perfume = perfume; this.referenceKey = referenceKey; }
    public void update(String referenceKey, String publicId, String slug, String name, BigDecimal likeRatio,
            Long nRecords, Integer position, JsonNode details) {
        this.referenceKey = referenceKey; this.relatedPublicId = publicId; this.relatedFragranceSlug = slug; this.relatedName = name;
        this.likeRatio = likeRatio; this.nRecords = nRecords; this.position = position; this.details = details.deepCopy();
    }
}
