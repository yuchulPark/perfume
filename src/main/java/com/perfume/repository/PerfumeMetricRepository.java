package com.perfume.repository;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.perfume.domain.PerfumeMetric;
public interface PerfumeMetricRepository extends JpaRepository<PerfumeMetric, Long> {
    List<PerfumeMetric> findByPerfume_IdOrderByMetricKeyAsc(Long perfumeId);
    Optional<PerfumeMetric> findByPerfume_IdAndMetricKey(Long perfumeId, String metricKey);
}
