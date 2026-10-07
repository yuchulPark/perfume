package com.perfume.domain;

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
@Table(name = "perfume_perfumers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_perfume_perfumers_perfume_perfumer",
                columnNames = {"perfume_id", "perfumer_id"})
}, indexes = {
        @Index(name = "idx_perfume_perfumers_perfumer_id", columnList = "perfumer_id", unique = false)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumePerfumer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "perfume_id", nullable = false)
    private Perfume perfume;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "perfumer_id", nullable = false)
    private Perfumer perfumer;

    public PerfumePerfumer(Perfume perfume, Perfumer perfumer) {
        this.perfume = perfume;
        this.perfumer = perfumer;
    }
}
