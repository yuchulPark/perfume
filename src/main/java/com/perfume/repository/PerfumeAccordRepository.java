package com.perfume.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.PerfumeAccord;

public interface PerfumeAccordRepository extends JpaRepository<PerfumeAccord, Long> {

    boolean existsByPerfume_IdAndAccord_Id(Long perfumeId, Long accordId);

    List<PerfumeAccord> findByPerfume_IdOrderByPositionAsc(Long perfumeId);
}
