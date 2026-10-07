package com.perfume.scentrev.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.perfume.domain.Accord;
import com.perfume.domain.Brand;
import com.perfume.domain.Note;
import com.perfume.domain.NoteLayer;
import com.perfume.domain.Perfume;
import com.perfume.domain.PerfumeAccord;
import com.perfume.domain.PerfumeNote;
import com.perfume.domain.PerfumePerfumer;
import com.perfume.domain.Perfumer;
import com.perfume.repository.AccordRepository;
import com.perfume.repository.BrandRepository;
import com.perfume.repository.NoteRepository;
import com.perfume.repository.PerfumeAccordRepository;
import com.perfume.repository.PerfumeNoteRepository;
import com.perfume.repository.PerfumePerfumerRepository;
import com.perfume.repository.PerfumeRepository;
import com.perfume.repository.PerfumerRepository;
import com.perfume.scentrev.dto.ScentRevAccords;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;
import com.perfume.scentrev.dto.ScentRevNotePyramid;
import com.perfume.scentrev.dto.ScentRevPerfumer;

/**
 * Persists Phase 1 data from an already-deserialized profile without network access.
 * Existing rows are reused: later imports only add missing masters/associations,
 * never delete associations, reorder them, or refresh stored metadata/accord metrics.
 * Portfolio fragrances and all Phase 2 data are ignored.
 */
@Service
public class ScentRevPhase1ImportService {

    private final BrandRepository brandRepository;
    private final PerfumeRepository perfumeRepository;
    private final PerfumerRepository perfumerRepository;
    private final PerfumePerfumerRepository perfumePerfumerRepository;
    private final NoteRepository noteRepository;
    private final PerfumeNoteRepository perfumeNoteRepository;
    private final AccordRepository accordRepository;
    private final PerfumeAccordRepository perfumeAccordRepository;

    public ScentRevPhase1ImportService(
            BrandRepository brandRepository,
            PerfumeRepository perfumeRepository,
            PerfumerRepository perfumerRepository,
            PerfumePerfumerRepository perfumePerfumerRepository,
            NoteRepository noteRepository,
            PerfumeNoteRepository perfumeNoteRepository,
            AccordRepository accordRepository,
            PerfumeAccordRepository perfumeAccordRepository) {
        this.brandRepository = brandRepository;
        this.perfumeRepository = perfumeRepository;
        this.perfumerRepository = perfumerRepository;
        this.perfumePerfumerRepository = perfumePerfumerRepository;
        this.noteRepository = noteRepository;
        this.perfumeNoteRepository = perfumeNoteRepository;
        this.accordRepository = accordRepository;
        this.perfumeAccordRepository = perfumeAccordRepository;
    }

    /**
     * Returns the new or existing perfume. Invalid identity, perfumer, or accord
     * entries fail before repository writes; null/blank note names are skipped
     * while retaining original source indexes for valid notes.
     *
     * <p>Repeated sequential imports do not insert duplicate rows. Concurrent
     * creates can still encounter database unique constraints; no retry policy
     * or synchronization is implemented here.
     *
     * @throws IllegalArgumentException if required data is missing or perfume
     *                                  identifiers conflict
     */
    @Transactional
    public Perfume importProfile(ScentRevFragranceProfileResponse profile) {
        ScentRevIdentity identity = validateProfile(profile);
        Perfume perfume = findExistingPerfume(identity).orElseGet(() -> createPerfume(identity));

        importPerfumers(perfume, profile.perfumers());
        importNotes(perfume, profile.notePyramid());
        importAccords(perfume, profile.accords());
        return perfume;
    }

    private ScentRevIdentity validateProfile(ScentRevFragranceProfileResponse profile) {
        if (profile == null) {
            throw new IllegalArgumentException("ScentRev profile is required");
        }
        ScentRevIdentity identity = profile.identity();
        if (identity == null) {
            throw new IllegalArgumentException("ScentRev identity is required");
        }
        requireText(identity.publicId(), "identity.publicId");
        requireText(identity.fragranceSlug(), "identity.fragranceSlug");
        requireText(identity.name(), "identity.name");
        requireText(identity.brandSlug(), "identity.brandSlug");
        requireText(identity.brandName(), "identity.brandName");

        if (profile.perfumers() != null) {
            for (int position = 0; position < profile.perfumers().size(); position++) {
                ScentRevPerfumer perfumer = profile.perfumers().get(position);
                String path = "perfumers[" + position + "]";
                if (perfumer == null) {
                    throw new IllegalArgumentException(path + " must not be null");
                }
                requireText(perfumer.perfumerId(), path + ".perfumerId");
                requireText(perfumer.name(), path + ".name");
            }
        }
        if (profile.accords() != null && profile.accords().accords() != null) {
            List<ScentRevAccords.Accord> accords = profile.accords().accords();
            for (int position = 0; position < accords.size(); position++) {
                ScentRevAccords.Accord accord = accords.get(position);
                String path = "accords.accords[" + position + "]";
                if (accord == null) {
                    throw new IllegalArgumentException(path + " must not be null");
                }
                requireText(accord.name(), path + ".name");
            }
        }
        return identity;
    }

    private Optional<Perfume> findExistingPerfume(ScentRevIdentity identity) {
        Optional<Perfume> byPublicId = perfumeRepository.findByScentrevPublicId(identity.publicId());
        // Check the slug even after a public-ID match to detect conflicting rows.
        Optional<Perfume> bySlug = perfumeRepository.findByFragranceSlug(identity.fragranceSlug());
        if (byPublicId.isPresent()) {
            Perfume perfume = byPublicId.get();
            if (bySlug.isPresent() && !samePerfumeRow(perfume, bySlug.get())) {
                throw identifierConflict(identity);
            }
            return byPublicId;
        }
        if (bySlug.isPresent()
                && !identity.publicId().equals(bySlug.get().getScentrevPublicId())) {
            throw identifierConflict(identity);
        }
        return bySlug;
    }

    private boolean samePerfumeRow(Perfume first, Perfume second) {
        return first == second || (first.getId() != null && first.getId().equals(second.getId()));
    }

    private IllegalArgumentException identifierConflict(ScentRevIdentity identity) {
        return new IllegalArgumentException("ScentRev identifier conflict for publicId '"
                + identity.publicId() + "' and fragranceSlug '" + identity.fragranceSlug() + "'");
    }

    private Perfume createPerfume(ScentRevIdentity identity) {
        Brand brand = brandRepository.findByBrandSlug(identity.brandSlug())
                .orElseGet(() -> brandRepository.save(
                        new Brand(identity.brandName().strip(), identity.brandSlug())));
        return perfumeRepository.save(new Perfume(
                identity.publicId(), identity.fragranceSlug(), identity.name().strip(),
                identity.releaseYear(), identity.description(), identity.reviewsCount(), brand));
    }

    private void importPerfumers(Perfume perfume, List<ScentRevPerfumer> sourcePerfumers) {
        if (sourcePerfumers == null) {
            return;
        }
        for (ScentRevPerfumer source : sourcePerfumers) {
            Perfumer perfumer = perfumerRepository.findByScentrevPerfumerId(source.perfumerId())
                    .orElseGet(() -> perfumerRepository.save(new Perfumer(
                            source.perfumerId(), source.name().strip(),
                            normalizeOptionalDescription(source.company()),
                            normalizeOptionalDescription(source.biography()), source.perfumesCount())));
            if (!perfumePerfumerRepository.existsByPerfume_IdAndPerfumer_Id(
                    perfume.getId(), perfumer.getId())) {
                perfumePerfumerRepository.save(new PerfumePerfumer(perfume, perfumer));
            }
        }
    }

    private void importNotes(Perfume perfume, ScentRevNotePyramid sourceNotes) {
        if (sourceNotes == null) {
            return;
        }
        importNoteLayer(perfume, sourceNotes.top(), NoteLayer.TOP);
        importNoteLayer(perfume, sourceNotes.middle(), NoteLayer.MIDDLE);
        importNoteLayer(perfume, sourceNotes.base(), NoteLayer.BASE);
    }

    private void importNoteLayer(Perfume perfume, List<String> sourceNotes, NoteLayer layer) {
        if (sourceNotes == null) {
            return;
        }
        for (int position = 0; position < sourceNotes.size(); position++) {
            String sourceName = sourceNotes.get(position);
            if (sourceName == null || sourceName.isBlank()) {
                continue;
            }
            String name = sourceName.strip();
            Note note = noteRepository.findByName(name)
                    .orElseGet(() -> noteRepository.save(new Note(name)));
            if (!perfumeNoteRepository.existsByPerfume_IdAndNote_IdAndLayer(
                    perfume.getId(), note.getId(), layer)) {
                perfumeNoteRepository.save(new PerfumeNote(perfume, note, layer, position));
            }
        }
    }

    private void importAccords(Perfume perfume, ScentRevAccords sourceAccords) {
        if (sourceAccords == null || sourceAccords.accords() == null) {
            return;
        }
        List<ScentRevAccords.Accord> accords = sourceAccords.accords();
        for (int position = 0; position < accords.size(); position++) {
            ScentRevAccords.Accord source = accords.get(position);
            String name = source.name().strip();
            Accord accord = accordRepository.findByName(name)
                    .orElseGet(() -> accordRepository.save(new Accord(name)));
            if (!perfumeAccordRepository.existsByPerfume_IdAndAccord_Id(
                    perfume.getId(), accord.getId())) {
                perfumeAccordRepository.save(new PerfumeAccord(
                        perfume, accord, source.percentage(), source.score(), position));
            }
        }
    }

    private void requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " must not be null or blank");
        }
    }

    // Only optional perfumer company/biography use this policy; identifiers stay untouched.
    private String normalizeOptionalDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() || normalized.equalsIgnoreCase("nan") ? null : normalized;
    }
}
