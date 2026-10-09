package com.perfume.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.perfume.domain.PerfumeAccord;

public interface PerfumeAccordRepository extends JpaRepository<PerfumeAccord, Long> {

    @Query("select a from PerfumeAccord a join fetch a.accord where a.perfume.id = :id order by a.position, a.id")
    List<PerfumeAccord> findDetailAccords(@Param("id") Long perfumeId);

    boolean existsByPerfume_IdAndAccord_Id(Long perfumeId, Long accordId);

    Optional<PerfumeAccord> findByPerfume_IdAndAccord_Id(Long perfumeId, Long accordId);

    List<PerfumeAccord> findByPerfume_IdOrderByPositionAsc(Long perfumeId);
}
