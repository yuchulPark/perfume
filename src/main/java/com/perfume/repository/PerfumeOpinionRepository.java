package com.perfume.repository;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.perfume.domain.PerfumeOpinion;
public interface PerfumeOpinionRepository extends JpaRepository<PerfumeOpinion, Long> {
    List<PerfumeOpinion> findByPerfume_IdOrderByPositionAscIdAsc(Long perfumeId);
    Optional<PerfumeOpinion> findByPerfume_IdAndKindAndTextHash(Long perfumeId, PerfumeOpinion.Kind kind, String textHash);
}
