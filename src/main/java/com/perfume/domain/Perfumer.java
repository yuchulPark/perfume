package com.perfume.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfumers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_perfumers_scentrev_perfumer_id", columnNames = "scentrev_perfumer_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Perfumer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "scentrev_perfumer_id", nullable = false, length = 128)
    private String scentrevPerfumerId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "company", length = 255)
    private String company;

    @Column(name = "biography", columnDefinition = "text")
    private String biography;

    @Column(name = "perfumes_count")
    private Long perfumesCount;

    public Perfumer(String scentrevPerfumerId, String name, String company,
                    String biography, Long perfumesCount) {
        this.scentrevPerfumerId = scentrevPerfumerId;
        this.name = name;
        this.company = company;
        this.biography = biography;
        this.perfumesCount = perfumesCount;
    }
}
