package com.perfume.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.Perfumer;

public interface PerfumerRepository extends JpaRepository<Perfumer, Long> {

    Optional<Perfumer> findByScentrevPerfumerId(String scentrevPerfumerId);

    boolean existsByScentrevPerfumerId(String scentrevPerfumerId);
}
