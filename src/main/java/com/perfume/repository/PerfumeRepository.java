package com.perfume.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.Perfume;

public interface PerfumeRepository extends JpaRepository<Perfume, Long> {

    Optional<Perfume> findByScentrevPublicId(String scentrevPublicId);

    Optional<Perfume> findByFragranceSlug(String fragranceSlug);

    boolean existsByScentrevPublicId(String scentrevPublicId);

    boolean existsByFragranceSlug(String fragranceSlug);
}
