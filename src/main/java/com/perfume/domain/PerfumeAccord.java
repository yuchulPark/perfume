package com.perfume.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfume_accords", uniqueConstraints = {
        @UniqueConstraint(name = "uk_perfume_accords_perfume_accord",
                columnNames = {"perfume_id", "accord_id"})
}, indexes = {
        @Index(name = "idx_perfume_accords_accord_id", columnList = "accord_id", unique = false)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeAccord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "perfume_id", nullable = false)
    private Perfume perfume;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accord_id", nullable = false)
    private Accord accord;

    @Column(name = "percentage")
    private Integer percentage;

    // Unconstrained PostgreSQL numeric avoids rounding provider decimals to a fixed scale.
    @Column(name = "score", columnDefinition = "numeric")
    private BigDecimal score;

    /** Zero-based index within the source accord array. */
    @Column(name = "position", nullable = false)
    private Integer position;

    public PerfumeAccord(Perfume perfume, Accord accord, Integer percentage,
                        BigDecimal score, Integer position) {
        this.perfume = perfume;
        this.accord = accord;
        this.percentage = percentage;
        this.score = score;
        this.position = position;
    }

    public void updateScentRevDetails(Integer percentage, BigDecimal score, Integer position) {
        this.percentage = percentage;
        this.score = score;
        this.position = position;
    }
}
