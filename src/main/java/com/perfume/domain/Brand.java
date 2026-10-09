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
@Table(name = "brands", uniqueConstraints = {
        @UniqueConstraint(name = "uk_brands_brand_slug", columnNames = "brand_slug")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Brand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "brand_slug", nullable = false, length = 255)
    private String brandSlug;

    public Brand(String name, String brandSlug) {
        this.name = name;
        this.brandSlug = brandSlug;
    }

    /** Change the display name while preserving this row's provider identity and relationships. */
    public void updateName(String name) {
        this.name = name;
    }
}
