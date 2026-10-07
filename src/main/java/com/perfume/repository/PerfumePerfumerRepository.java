package com.perfume.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.PerfumePerfumer;

public interface PerfumePerfumerRepository extends JpaRepository<PerfumePerfumer, Long> {

    boolean existsByPerfume_IdAndPerfumer_Id(Long perfumeId, Long perfumerId);
}
