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
@Table(name = "perfumes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_perfumes_scentrev_public_id", columnNames = "scentrev_public_id"),
        @UniqueConstraint(name = "uk_perfumes_fragrance_slug", columnNames = "fragrance_slug")
}, indexes = {
        @Index(name = "idx_perfumes_brand_id", columnList = "brand_id", unique = false)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Perfume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "scentrev_public_id", nullable = false, length = 128)
    private String scentrevPublicId;

    @Column(name = "fragrance_slug", nullable = false, length = 255)
    private String fragranceSlug;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "release_year")
    private Integer releaseYear;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "reviews_count")
    private Long reviewsCount;

    @Column(name = "image_url", columnDefinition = "text")
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    public Perfume(String scentrevPublicId, String fragranceSlug, String name,
                   Integer releaseYear, String description, Long reviewsCount, Brand brand) {
        this.scentrevPublicId = scentrevPublicId;
        this.fragranceSlug = fragranceSlug;
        this.name = name;
        this.releaseYear = releaseYear;
        this.description = description;
        this.reviewsCount = reviewsCount;
        this.brand = brand;
    }

    /** Refresh provider-owned details without replacing the row or changing its Brand. */
    public void updateScentRevDetails(String fragranceSlug, String name, Integer releaseYear,
            String description, Long reviewsCount) {
        this.fragranceSlug = fragranceSlug;
        this.name = name;
        this.releaseYear = releaseYear;
        this.description = description;
        this.reviewsCount = reviewsCount;
    }

    /** Only a supplied provider URL is accepted; absence never clears an existing URL. */
    public boolean updateImageUrl(String suppliedUrl) {
        if (suppliedUrl == null || suppliedUrl.isBlank() || suppliedUrl.equals(imageUrl)) { return false; }
        imageUrl = suppliedUrl;
        return true;
    }
}
