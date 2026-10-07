package com.perfume.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "perfume_notes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_perfume_notes_perfume_note_layer",
                columnNames = {"perfume_id", "note_id", "layer"})
}, indexes = {
        @Index(name = "idx_perfume_notes_note_id", columnList = "note_id", unique = false)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PerfumeNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "perfume_id", nullable = false)
    private Perfume perfume;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private Note note;

    @Enumerated(EnumType.STRING)
    @Column(name = "layer", nullable = false, length = 6)
    private NoteLayer layer;

    /** Zero-based index within the source array for this note layer. */
    @Column(name = "position", nullable = false)
    private Integer position;

    public PerfumeNote(Perfume perfume, Note note, NoteLayer layer, Integer position) {
        this.perfume = perfume;
        this.note = note;
        this.layer = layer;
        this.position = position;
    }
}
