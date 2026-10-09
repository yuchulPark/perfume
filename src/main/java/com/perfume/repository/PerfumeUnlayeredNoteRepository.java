package com.perfume.repository;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.perfume.domain.PerfumeUnlayeredNote;
public interface PerfumeUnlayeredNoteRepository extends JpaRepository<PerfumeUnlayeredNote, Long> {
    @Query("select n from PerfumeUnlayeredNote n join fetch n.note where n.perfume.id = :id order by n.position, n.id")
    List<PerfumeUnlayeredNote> findDetailNotes(@Param("id") Long perfumeId);
    Optional<PerfumeUnlayeredNote> findByPerfume_IdAndNote_Id(Long perfumeId, Long noteId);
}
