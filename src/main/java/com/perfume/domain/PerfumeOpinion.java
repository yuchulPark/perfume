package com.perfume.domain;

import java.math.BigDecimal;
import com.fasterxml.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfume_opinions", uniqueConstraints = @UniqueConstraint(name = "uk_perfume_opinions_text", columnNames = {"perfume_id", "kind", "text_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeOpinion {
    public enum Kind { PRO, CON }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "perfume_id", nullable = false) private Perfume perfume;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 8) private Kind kind;
    @Column(name = "text_hash", nullable = false, length = 64) private String textHash;
    @Column(name = "opinion_text", columnDefinition = "text", nullable = false) private String text;
    @Column(columnDefinition = "text") private String source;
    private Integer position;
    @Column(name = "like_ratio", columnDefinition = "numeric") private BigDecimal likeRatio;
    @Column(name = "n_records") private Long nRecords;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb", nullable = false) private JsonNode details;

    public PerfumeOpinion(Perfume perfume, Kind kind, String textHash, String text) {
        this.perfume = perfume; this.kind = kind; this.textHash = textHash; this.text = text;
    }
    public void update(String source, Integer position, BigDecimal likeRatio, Long nRecords, JsonNode details) {
        this.source = source; this.position = position; this.likeRatio = likeRatio; this.nRecords = nRecords; this.details = details.deepCopy();
    }
}
