package com.perfume.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.perfume.domain.PerfumePerfumer;

public interface PerfumePerfumerRepository extends JpaRepository<PerfumePerfumer, Long> {

    @Query("select p from PerfumePerfumer p join fetch p.perfumer where p.perfume.id = :id order by p.perfumer.name, p.id")
    List<PerfumePerfumer> findDetailPerfumers(@Param("id") Long perfumeId);

    boolean existsByPerfume_IdAndPerfumer_Id(Long perfumeId, Long perfumerId);
}
