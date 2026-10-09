package com.perfume.scentrev.service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
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
 * The original importProfile operation adds missing masters/associations.
 * Explicit seeded-brand page imports also refresh supported provider-owned fields in place.
 * Neither operation deletes rows or stale historical relationships.
 * The full-profile extension joins the same transaction and preserves unknown source JSON.
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
    private final ScentRevProfileDataService profileData;

    public ScentRevPhase1ImportService(
            BrandRepository brandRepository,
            PerfumeRepository perfumeRepository,
            PerfumerRepository perfumerRepository,
            PerfumePerfumerRepository perfumePerfumerRepository,
            NoteRepository noteRepository,
            PerfumeNoteRepository perfumeNoteRepository,
            AccordRepository accordRepository,
            PerfumeAccordRepository perfumeAccordRepository) {
        this(brandRepository, perfumeRepository, perfumerRepository, perfumePerfumerRepository, noteRepository,
                perfumeNoteRepository, accordRepository, perfumeAccordRepository, null);
    }

    @Autowired
    public ScentRevPhase1ImportService(BrandRepository brandRepository, PerfumeRepository perfumeRepository,
            PerfumerRepository perfumerRepository, PerfumePerfumerRepository perfumePerfumerRepository,
            NoteRepository noteRepository, PerfumeNoteRepository perfumeNoteRepository, AccordRepository accordRepository,
            PerfumeAccordRepository perfumeAccordRepository, ScentRevProfileDataService profileData) {
        this.brandRepository = brandRepository;
        this.perfumeRepository = perfumeRepository;
        this.perfumerRepository = perfumerRepository;
        this.perfumePerfumerRepository = perfumePerfumerRepository;
        this.noteRepository = noteRepository;
        this.perfumeNoteRepository = perfumeNoteRepository;
        this.accordRepository = accordRepository;
        this.perfumeAccordRepository = perfumeAccordRepository;
        this.profileData = profileData;
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
        if (profileData != null) { profileData.validate(profile); }
        Perfume perfume = findExistingPerfume(identity).orElseGet(() -> createPerfume(identity));

        importPerfumers(perfume, profile.perfumers());
        importNotes(perfume, profile.notePyramid());
        importAccords(perfume, profile.accords());
        if (profileData != null) { profileData.save(perfume, profile); }
        return perfume;
    }

    /** Pure validation, callable outside a transaction after all profiles for a page have been fetched. */
    public void validateProfilePageForBrand(String brandSlug, List<ScentRevFragranceProfileResponse> profiles) {
        requireText(brandSlug, "selected brand slug");
        if (profiles == null || profiles.size() > 10) {
            throw new IllegalArgumentException("A profile page must contain at most 10 profiles.");
        }
        Set<String> publicIds = new HashSet<>();
        Set<String> slugs = new HashSet<>();
        for (var profile : profiles) {
            var identity = validateProfile(profile);
            if (profileData != null) { profileData.validate(profile); }
            if (!brandSlug.equals(identity.brandSlug())) {
                throw new IllegalArgumentException("Profile brand slug does not match the selected seeded Brand.");
            }
            requireIdentifier(identity.publicId(), 128, "identity.publicId");
            requireIdentifier(identity.fragranceSlug(), 255, "identity.fragranceSlug");
            requireBoundedText(identity.name(), 255, "identity.name");
            if (!publicIds.add(identity.publicId()) || !slugs.add(identity.fragranceSlug())) {
                throw new IllegalArgumentException("A profile page contains conflicting/duplicate fragrance identities.");
            }
            if (profile.perfumers() != null) {
                for (var perfumer : profile.perfumers()) {
                    requireIdentifier(perfumer.perfumerId(), 128, "perfumer.perfumerId");
                    requireBoundedText(perfumer.name(), 255, "perfumer.name");
                    String company = normalizeOptionalDescription(perfumer.company());
                    if (company != null && company.length() > 255) {
                        throw new IllegalArgumentException("Perfumer company exceeds its existing column length.");
                    }
                }
            }
            if (profile.notePyramid() != null) {
                var notes = profile.notePyramid();
                validateSectionIdentity(identity, notes.publicId(), notes.brandSlug(), notes.fragranceSlug());
                validateNoteNames(notes.top());
                validateNoteNames(notes.middle());
                validateNoteNames(notes.base());
            }
            if (profile.accords() != null) {
                var accords = profile.accords();
                validateSectionIdentity(identity, accords.publicId(), accords.brandSlug(), accords.fragranceSlug());
                if (accords.accords() != null) {
                    accords.accords().forEach(accord -> requireBoundedText(accord.name(), 255, "accord.name"));
                }
            }
        }
    }

    /** One committed page, containing only persistence. Never creates or renames a Brand. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScentRevPhase1PageImportResult importPageForSeededBrand(Long brandId, String brandSlug,
            List<ScentRevFragranceProfileResponse> profiles) {
        validateProfilePageForBrand(brandSlug, profiles);
        Brand brand = brandRepository.findById(brandId)
                .filter(existing -> brandSlug.equals(existing.getBrandSlug()))
                .orElseThrow(() -> new IdentityConflictException("The selected seeded Brand no longer exists with that identity."));
        var counts = new PageCounts();
        for (var profile : profiles) {
            var identity = profile.identity();
            Optional<Perfume> existing;
            try { existing = findExistingPerfume(identity); }
            catch (IllegalArgumentException exception) {
                throw new IdentityConflictException("Existing perfume public ID and fragrance slug identify conflicting rows.");
            }
            boolean inserted = existing.isEmpty();
            Perfume perfume;
            boolean detailsChanged = false;
            if (inserted) {
                perfume = perfumeRepository.save(new Perfume(identity.publicId(), identity.fragranceSlug(),
                        identity.name().strip(), identity.releaseYear(), identity.description(), identity.reviewsCount(), brand));
            } else {
                perfume = existing.get();
                if (perfume.getBrand() == null || !brandId.equals(perfume.getBrand().getId())
                        || !brandSlug.equals(perfume.getBrand().getBrandSlug())) {
                    throw new IdentityConflictException("Existing perfume belongs to another Brand; reassignment is prohibited.");
                }
                Integer releaseYear = identity.releaseYear() != null ? identity.releaseYear() : perfume.getReleaseYear();
                String description = identity.description() != null && !identity.description().isBlank() ? identity.description() : perfume.getDescription();
                Long reviewsCount = identity.reviewsCount() != null ? identity.reviewsCount() : perfume.getReviewsCount();
                detailsChanged = !Objects.equals(perfume.getFragranceSlug(), identity.fragranceSlug())
                        || !Objects.equals(perfume.getName(), identity.name().strip())
                        || !Objects.equals(perfume.getReleaseYear(), releaseYear)
                        || !Objects.equals(perfume.getDescription(), description)
                        || !Objects.equals(perfume.getReviewsCount(), reviewsCount);
                if (detailsChanged) {
                    perfume.updateScentRevDetails(identity.fragranceSlug(), identity.name().strip(), releaseYear,
                            description, reviewsCount);
                }
            }
            int changesBefore = counts.changes;
            importPerfumers(perfume, profile.perfumers(), counts);
            importNotes(perfume, profile.notePyramid(), counts);
            importAccords(perfume, profile.accords(), counts);
            boolean extendedChanged = profileData != null && profileData.save(perfume, profile);
            if (inserted) { counts.inserted++; }
            else if (detailsChanged || extendedChanged || counts.changes != changesBefore) { counts.updated++; }
            else { counts.unchanged++; }
        }
        return new ScentRevPhase1PageImportResult(counts.inserted, counts.updated, counts.unchanged,
                counts.perfumerIds, counts.noteNames, counts.accordNames,
                counts.perfumerLinks, counts.noteLinks, counts.accordLinks);
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
        importPerfumers(perfume, sourcePerfumers, null);
    }

    private void importPerfumers(Perfume perfume, List<ScentRevPerfumer> sourcePerfumers, PageCounts counts) {
        if (sourcePerfumers == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ScentRevPerfumer source : sourcePerfumers) {
            if (counts != null && !seen.add(source.perfumerId())) { continue; }
            Perfumer perfumer = perfumerRepository.findByScentrevPerfumerId(source.perfumerId())
                    .orElseGet(() -> {
                        markChanged(counts);
                        return perfumerRepository.save(new Perfumer(
                            source.perfumerId(), source.name().strip(),
                            normalizeOptionalDescription(source.company()),
                            normalizeOptionalDescription(source.biography()), source.perfumesCount()));
                    });
            if (counts != null) {
                String company = normalizeOptionalDescription(source.company());
                String biography = normalizeOptionalDescription(source.biography());
                company = company != null ? company : perfumer.getCompany();
                biography = biography != null ? biography : perfumer.getBiography();
                Long perfumesCount = source.perfumesCount() != null ? source.perfumesCount() : perfumer.getPerfumesCount();
                if (!Objects.equals(perfumer.getName(), source.name().strip())
                        || !Objects.equals(perfumer.getCompany(), company)
                        || !Objects.equals(perfumer.getBiography(), biography)
                        || !Objects.equals(perfumer.getPerfumesCount(), perfumesCount)) {
                    perfumer.updateScentRevDetails(source.name().strip(), company, biography, perfumesCount);
                    markChanged(counts);
                }
                counts.perfumerIds.add(source.perfumerId());
                counts.perfumerLinks++;
            }
            if (!perfumePerfumerRepository.existsByPerfume_IdAndPerfumer_Id(
                    perfume.getId(), perfumer.getId())) {
                perfumePerfumerRepository.save(new PerfumePerfumer(perfume, perfumer));
                markChanged(counts);
            }
        }
    }

    private void importNotes(Perfume perfume, ScentRevNotePyramid sourceNotes) {
        importNotes(perfume, sourceNotes, null);
    }

    private void importNotes(Perfume perfume, ScentRevNotePyramid sourceNotes, PageCounts counts) {
        if (sourceNotes == null) {
            return;
        }
        importNoteLayer(perfume, sourceNotes.top(), NoteLayer.TOP, counts);
        importNoteLayer(perfume, sourceNotes.middle(), NoteLayer.MIDDLE, counts);
        importNoteLayer(perfume, sourceNotes.base(), NoteLayer.BASE, counts);
    }

    private void importNoteLayer(Perfume perfume, List<String> sourceNotes, NoteLayer layer, PageCounts counts) {
        if (sourceNotes == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (int position = 0; position < sourceNotes.size(); position++) {
            String sourceName = sourceNotes.get(position);
            if (sourceName == null || sourceName.isBlank()) {
                continue;
            }
            String name = sourceName.strip();
            if (counts != null && !seen.add(name)) { continue; }
            Note note = noteRepository.findByName(name)
                    .orElseGet(() -> { markChanged(counts); return noteRepository.save(new Note(name)); });
            if (counts != null) {
                counts.noteNames.add(name);
                counts.noteLinks++;
                var existing = perfumeNoteRepository.findByPerfume_IdAndNote_IdAndLayer(perfume.getId(), note.getId(), layer);
                if (existing.isEmpty()) {
                    perfumeNoteRepository.save(new PerfumeNote(perfume, note, layer, position));
                    markChanged(counts);
                } else if (!Objects.equals(existing.get().getPosition(), position)) {
                    existing.get().updatePosition(position);
                    markChanged(counts);
                }
            } else if (!perfumeNoteRepository.existsByPerfume_IdAndNote_IdAndLayer(
                    perfume.getId(), note.getId(), layer)) {
                perfumeNoteRepository.save(new PerfumeNote(perfume, note, layer, position));
            }
        }
    }

    private void importAccords(Perfume perfume, ScentRevAccords sourceAccords) {
        importAccords(perfume, sourceAccords, null);
    }

    private void importAccords(Perfume perfume, ScentRevAccords sourceAccords, PageCounts counts) {
        if (sourceAccords == null || sourceAccords.accords() == null) {
            return;
        }
        List<ScentRevAccords.Accord> accords = sourceAccords.accords();
        Set<String> seen = new HashSet<>();
        for (int position = 0; position < accords.size(); position++) {
            ScentRevAccords.Accord source = accords.get(position);
            String name = source.name().strip();
            if (counts != null && !seen.add(name)) { continue; }
            Accord accord = accordRepository.findByName(name)
                    .orElseGet(() -> { markChanged(counts); return accordRepository.save(new Accord(name)); });
            if (counts != null) {
                counts.accordNames.add(name);
                counts.accordLinks++;
                var existing = perfumeAccordRepository.findByPerfume_IdAndAccord_Id(perfume.getId(), accord.getId());
                if (existing.isEmpty()) {
                    perfumeAccordRepository.save(new PerfumeAccord(perfume, accord, source.percentage(), source.score(), position));
                    markChanged(counts);
                } else {
                    Integer percentage = source.percentage() != null ? source.percentage() : existing.get().getPercentage();
                    BigDecimal score = source.score() != null ? source.score() : existing.get().getScore();
                    if (!Objects.equals(existing.get().getPercentage(), percentage)
                            || !sameDecimal(existing.get().getScore(), score)
                            || !Objects.equals(existing.get().getPosition(), position)) {
                        existing.get().updateScentRevDetails(percentage, score, position);
                        markChanged(counts);
                    }
                }
            } else if (!perfumeAccordRepository.existsByPerfume_IdAndAccord_Id(
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

    private void requireBoundedText(String value, int length, String path) {
        requireText(value, path);
        if (value.strip().length() > length) {
            throw new IllegalArgumentException(path + " exceeds its existing column length");
        }
    }

    private void requireIdentifier(String value, int length, String path) {
        requireText(value, path);
        if (value.length() > length || !value.equals(value.strip())) {
            throw new IllegalArgumentException(path + " must be an exact identifier within its existing column length");
        }
    }

    private void validateNoteNames(List<String> notes) {
        if (notes != null) {
            notes.stream().filter(name -> name != null && !name.isBlank())
                    .forEach(name -> requireBoundedText(name, 255, "note.name"));
        }
    }

    private void validateSectionIdentity(ScentRevIdentity identity, String publicId, String brandSlug, String fragranceSlug) {
        if ((publicId != null && !publicId.equals(identity.publicId()))
                || (brandSlug != null && !brandSlug.equals(identity.brandSlug()))
                || (fragranceSlug != null && !fragranceSlug.equals(identity.fragranceSlug()))) {
            throw new IllegalArgumentException("Phase-1 section identity conflicts with the profile identity.");
        }
    }

    private static boolean sameDecimal(BigDecimal first, BigDecimal second) {
        return first == null ? second == null : second != null && first.compareTo(second) == 0;
    }

    private static void markChanged(PageCounts counts) { if (counts != null) { counts.changes++; } }

    private static class PageCounts {
        private int inserted, updated, unchanged, changes, perfumerLinks, noteLinks, accordLinks;
        private final Set<String> perfumerIds = new HashSet<>();
        private final Set<String> noteNames = new HashSet<>();
        private final Set<String> accordNames = new HashSet<>();
    }

    /** Explicit safe diagnostics from the seeded-brand persistence path, without SQL/provider payloads. */
    public static class IdentityConflictException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private IdentityConflictException(String message) { super(message); }
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
