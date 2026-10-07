package com.perfume.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.Brand;

public interface BrandRepository extends JpaRepository<Brand, Long> {

    Optional<Brand> findByBrandSlug(String brandSlug);

    boolean existsByBrandSlug(String brandSlug);
}
