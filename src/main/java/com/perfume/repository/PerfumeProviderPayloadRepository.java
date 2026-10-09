package com.perfume.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import com.perfume.domain.PerfumeProviderPayload;
public interface PerfumeProviderPayloadRepository extends JpaRepository<PerfumeProviderPayload, Long> {
    boolean existsByPerfume_IdAndToolNameAndPayloadHash(Long perfumeId, String toolName, String payloadHash);
}
