package com.perfume.repository;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.perfume.domain.PerfumeSimilarity;
public interface PerfumeSimilarityRepository extends JpaRepository<PerfumeSimilarity, Long> {
    List<PerfumeSimilarity> findByPerfume_IdOrderByPositionAscIdAsc(Long perfumeId);
    Optional<PerfumeSimilarity> findByPerfume_IdAndReferenceKey(Long perfumeId, String referenceKey);
    Optional<PerfumeSimilarity> findByPerfume_IdAndRelatedFragranceSlug(Long perfumeId, String relatedFragranceSlug);
}
