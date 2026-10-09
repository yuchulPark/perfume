package com.perfume.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.perfume.domain.NoteLayer;
import com.perfume.domain.PerfumeNote;

public interface PerfumeNoteRepository extends JpaRepository<PerfumeNote, Long> {

    @Query("select n from PerfumeNote n join fetch n.note where n.perfume.id = :id order by n.position, n.id")
    List<PerfumeNote> findDetailNotes(@Param("id") Long perfumeId);

    boolean existsByPerfume_IdAndNote_IdAndLayer(Long perfumeId, Long noteId, NoteLayer layer);

    Optional<PerfumeNote> findByPerfume_IdAndNote_IdAndLayer(Long perfumeId, Long noteId, NoteLayer layer);

    /**
     * Orders by position only. Positions restart within each layer, so this does
     * not impose TOP, MIDDLE, BASE ordering.
     */
    List<PerfumeNote> findByPerfume_IdOrderByPositionAsc(Long perfumeId);
}
