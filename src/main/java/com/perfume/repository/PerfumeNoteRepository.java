package com.perfume.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.perfume.domain.NoteLayer;
import com.perfume.domain.PerfumeNote;

public interface PerfumeNoteRepository extends JpaRepository<PerfumeNote, Long> {

    boolean existsByPerfume_IdAndNote_IdAndLayer(Long perfumeId, Long noteId, NoteLayer layer);

    /**
     * Orders by position only. Positions restart within each layer, so this does
     * not impose TOP, MIDDLE, BASE ordering.
     */
    List<PerfumeNote> findByPerfume_IdOrderByPositionAsc(Long perfumeId);
}
