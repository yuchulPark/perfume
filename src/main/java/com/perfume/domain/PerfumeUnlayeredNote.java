package com.perfume.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Notes supplied without a pyramid layer; never invents TOP/MIDDLE/BASE placement. */
@Entity
@Table(name = "perfume_unlayered_notes", uniqueConstraints = @UniqueConstraint(name = "uk_perfume_unlayered_notes_pair", columnNames = {"perfume_id", "note_id"}),
        indexes = @Index(name = "idx_perfume_unlayered_notes_note_id", columnList = "note_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeUnlayeredNote {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "perfume_id", nullable = false) private Perfume perfume;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "note_id", nullable = false) private Note note;
    private Integer position;
    public PerfumeUnlayeredNote(Perfume perfume, Note note, Integer position) { this.perfume = perfume; this.note = note; this.position = position; }
    public void updatePosition(Integer position) { this.position = position; }
}
