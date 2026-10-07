package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Repository mocks retain saved rows across calls; no Spring context or database is used. */
class ScentRevPhase1ImportServiceTests {

    private final BrandRepository brandRepository = mock(BrandRepository.class);
    private final PerfumeRepository perfumeRepository = mock(PerfumeRepository.class);
    private final PerfumerRepository perfumerRepository = mock(PerfumerRepository.class);
    private final PerfumePerfumerRepository perfumePerfumerRepository = mock(PerfumePerfumerRepository.class);
    private final NoteRepository noteRepository = mock(NoteRepository.class);
    private final PerfumeNoteRepository perfumeNoteRepository = mock(PerfumeNoteRepository.class);
    private final AccordRepository accordRepository = mock(AccordRepository.class);
    private final PerfumeAccordRepository perfumeAccordRepository = mock(PerfumeAccordRepository.class);

    private final Map<String, Brand> brands = new LinkedHashMap<>();
    private final Map<String, Perfume> perfumes = new LinkedHashMap<>();
    private final Map<String, Perfumer> perfumers = new LinkedHashMap<>();
    private final Map<String, Note> notes = new LinkedHashMap<>();
    private final Map<String, Accord> accords = new LinkedHashMap<>();
    private final List<PerfumePerfumer> perfumerRows = new ArrayList<>();
    private final List<PerfumeNote> noteRows = new ArrayList<>();
    private final List<PerfumeAccord> accordRows = new ArrayList<>();
    private long nextId = 1;
    private ScentRevPhase1ImportService service;

    @BeforeEach
    void setUp() {
        when(brandRepository.findByBrandSlug(anyString()))
                .thenAnswer(call -> Optional.ofNullable(brands.get(call.getArgument(0))));
        when(brandRepository.save(any(Brand.class))).thenAnswer(call -> rememberBrand(call.getArgument(0)));
        when(perfumeRepository.findByScentrevPublicId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(perfumes.get(call.getArgument(0))));
        when(perfumeRepository.findByFragranceSlug(anyString())).thenAnswer(call -> perfumes.values().stream()
                .filter(perfume -> perfume.getFragranceSlug().equals(call.getArgument(0))).findFirst());
        when(perfumeRepository.save(any(Perfume.class))).thenAnswer(call -> rememberPerfume(call.getArgument(0)));
        when(perfumerRepository.findByScentrevPerfumerId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(perfumers.get(call.getArgument(0))));
        when(perfumerRepository.save(any(Perfumer.class))).thenAnswer(call -> rememberPerfumer(call.getArgument(0)));
        when(noteRepository.findByName(anyString()))
                .thenAnswer(call -> Optional.ofNullable(notes.get(call.getArgument(0))));
        when(noteRepository.save(any(Note.class))).thenAnswer(call -> rememberNote(call.getArgument(0)));
        when(accordRepository.findByName(anyString()))
                .thenAnswer(call -> Optional.ofNullable(accords.get(call.getArgument(0))));
        when(accordRepository.save(any(Accord.class))).thenAnswer(call -> rememberAccord(call.getArgument(0)));

        when(perfumePerfumerRepository.existsByPerfume_IdAndPerfumer_Id(any(), any()))
                .thenAnswer(call -> perfumerRows.stream().anyMatch(row ->
                        row.getPerfume().getId().equals(call.getArgument(0))
                                && row.getPerfumer().getId().equals(call.getArgument(1))));
        when(perfumePerfumerRepository.save(any(PerfumePerfumer.class))).thenAnswer(call -> {
            PerfumePerfumer row = call.getArgument(0);
            perfumerRows.add(row);
            return row;
        });
        when(perfumeNoteRepository.existsByPerfume_IdAndNote_IdAndLayer(any(), any(), any()))
                .thenAnswer(call -> noteRows.stream().anyMatch(row ->
                        row.getPerfume().getId().equals(call.getArgument(0))
                                && row.getNote().getId().equals(call.getArgument(1))
                                && row.getLayer() == call.getArgument(2)));
        when(perfumeNoteRepository.save(any(PerfumeNote.class))).thenAnswer(call -> {
            PerfumeNote row = call.getArgument(0);
            noteRows.add(row);
            return row;
        });
        when(perfumeAccordRepository.existsByPerfume_IdAndAccord_Id(any(), any()))
                .thenAnswer(call -> accordRows.stream().anyMatch(row ->
                        row.getPerfume().getId().equals(call.getArgument(0))
                                && row.getAccord().getId().equals(call.getArgument(1))));
        when(perfumeAccordRepository.save(any(PerfumeAccord.class))).thenAnswer(call -> {
            PerfumeAccord row = call.getArgument(0);
            accordRows.add(row);
            return row;
        });
        service = new ScentRevPhase1ImportService(brandRepository, perfumeRepository, perfumerRepository,
                perfumePerfumerRepository, noteRepository, perfumeNoteRepository,
                accordRepository, perfumeAccordRepository);
    }

    @Test
    void importsRichProfileIntoAllEightPhase1Structures() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");

        Perfume perfume = service.importProfile(profile);

        assertThat(perfume.getId()).isNotNull();
        assertThat(perfume.getScentrevPublicId()).isEqualTo(profile.identity().publicId());
        assertThat(perfume.getFragranceSlug()).isEqualTo(profile.identity().fragranceSlug());
        assertThat(perfume.getName()).isEqualTo("Aventus");
        assertThat(perfume.getReleaseYear()).isEqualTo(2010);
        assertThat(perfume.getDescription()).isEqualTo(profile.identity().description());
        assertThat(perfume.getReviewsCount()).isEqualTo(26169L);
        assertThat(perfume.getBrand()).isSameAs(brands.get("creed"));
        assertThat(perfume.getBrand().getName()).isEqualTo("Creed");
        assertThat(perfumers.keySet()).containsExactly("p138", "p139");
        assertThat(perfumers.get("p138").getName()).isEqualTo("Olivier Creed");
        assertThat(perfumers.get("p138").getCompany()).isEqualTo("Creed");
        assertThat(perfumers.get("p138").getBiography()).isEqualTo(profile.perfumers().get(0).biography());
        assertThat(perfumers.get("p138").getPerfumesCount()).isEqualTo(120L);
        assertThat(perfumerRows).extracting(row -> row.getPerfumer().getScentrevPerfumerId())
                .containsExactly("p138", "p139");
        assertThat(noteRows).extracting(row -> row.getNote().getName(), PerfumeNote::getLayer, PerfumeNote::getPosition)
                .containsExactly(tuple("Pineapple", NoteLayer.TOP, 0), tuple("Bergamot", NoteLayer.TOP, 1),
                        tuple("Birch", NoteLayer.MIDDLE, 0), tuple("Jasmine", NoteLayer.MIDDLE, 1),
                        tuple("Musk", NoteLayer.BASE, 0), tuple("Oakmoss", NoteLayer.BASE, 1));
        assertThat(accordRows).extracting(row -> row.getAccord().getName(), PerfumeAccord::getPercentage,
                        PerfumeAccord::getScore, PerfumeAccord::getPosition)
                .containsExactly(tuple("fruity", 100, new BigDecimal("1.0"), 0),
                        tuple("smoky", 68, new BigDecimal("0.68"), 1),
                        tuple("woody", 67, new BigDecimal("0.67"), 2));
        assertThat(accordRows.stream().mapToInt(PerfumeAccord::getPercentage).sum()).isEqualTo(235);
        assertThat(perfumerRows).allSatisfy(row -> assertThat(row.getPerfume()).isSameAs(perfume));
        assertThat(noteRows).allSatisfy(row -> assertThat(row.getPerfume()).isSameAs(perfume));
        assertThat(accordRows).allSatisfy(row -> assertThat(row.getPerfume()).isSameAs(perfume));
        // Rich fixture includes portfolio and Phase 2 data, but only its main perfume is imported.
        assertThat(perfumes).hasSize(1);
        verifySaveCounts(1, 1, 2, 2, 6, 6, 3, 3);
    }

    @Test
    void importsSparseProfileWithNullYearAndNoChildren() throws IOException {
        Perfume perfume = service.importProfile(readProfile("sparse-profile.json"));

        assertThat(perfume.getReleaseYear()).isNull();
        assertThat(perfume.getDescription()).isNull();
        assertThat(perfume.getReviewsCount()).isEqualTo(1L);
        verifyNoChildRepositoryInteractions();
        verifySaveCounts(1, 1, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void acceptsAbsentChildSectionsAndMissingOptionalIdentityNumbers() {
        Perfume perfume = service.importProfile(profile(identity(), null, null, null));

        assertThat(perfume.getReleaseYear()).isNull();
        assertThat(perfume.getReviewsCount()).isNull();
        verifyNoChildRepositoryInteractions();
    }

    @Test
    void acceptsNullLayerAndAccordArraysWhileImportingPopulatedLayer() {
        service.importProfile(profile(identity(), null, notePyramid(null, List.of(), List.of("Musk")),
                accordList(null)));

        assertThat(noteRows).singleElement().satisfies(row -> {
            assertThat(row.getLayer()).isEqualTo(NoteLayer.BASE);
            assertThat(row.getPosition()).isZero();
        });
        verifyNoInteractions(perfumerRepository, perfumePerfumerRepository, accordRepository, perfumeAccordRepository);
    }

    @Test
    void normalizesFixtureNanWithoutChangingDto() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");

        service.importProfile(profile);

        assertThat(perfumers.get("p139").getCompany()).isNull();
        assertThat(perfumers.get("p139").getBiography()).isNull();
        assertThat(perfumers.get("p139").getPerfumesCount()).isEqualTo(42L);
        assertThat(profile.perfumers().get(1).company()).isEqualTo("nan");
        assertThat(profile.perfumers().get(1).biography()).isEqualTo("nan");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t ", "nan", " NaN ", "NAN"})
    void normalizesUnavailablePerfumerDescriptionsOnly(String value) {
        ScentRevPerfumer source = new ScentRevPerfumer("Perfumer", value, value, "p1", null, null);

        service.importProfile(profile(identity(), List.of(source), null, null));

        assertThat(perfumers.get("p1").getCompany()).isNull();
        assertThat(perfumers.get("p1").getBiography()).isNull();
        assertThat(perfumers.get("p1").getPerfumesCount()).isNull();
        assertThat(source.company()).isEqualTo(value);
        assertThat(source.biography()).isEqualTo(value);
    }

    @Test
    void trimsRealDescriptionsAndDisplayNamesWithoutNormalizingIdentifiersOrNanNames() {
        ScentRevIdentity sourceIdentity = new ScentRevIdentity(" nan ", " public-ID ", " Mixed Case House ",
                " Brand-Slug ", " Fragrance-Slug ", " nan ", null, null, null, null);
        ScentRevPerfumer sourcePerfumer = new ScentRevPerfumer(" Named Perfumer ", " Studio Name ",
                " Biography text. ", " perfumer-ID ", null, null);

        Perfume perfume = service.importProfile(profile(sourceIdentity, List.of(sourcePerfumer), null, null));

        assertThat(perfume.getName()).isEqualTo("nan");
        assertThat(perfume.getDescription()).isEqualTo(" nan ");
        assertThat(perfume.getScentrevPublicId()).isEqualTo(" public-ID ");
        assertThat(perfume.getFragranceSlug()).isEqualTo(" Fragrance-Slug ");
        assertThat(perfume.getBrand().getName()).isEqualTo("Mixed Case House");
        assertThat(perfume.getBrand().getBrandSlug()).isEqualTo(" Brand-Slug ");
        Perfumer perfumer = perfumers.get(" perfumer-ID ");
        assertThat(perfumer.getName()).isEqualTo("Named Perfumer");
        assertThat(perfumer.getCompany()).isEqualTo("Studio Name");
        assertThat(perfumer.getBiography()).isEqualTo("Biography text.");
        assertThat(sourcePerfumer.company()).isEqualTo(" Studio Name ");
        assertThat(sourceIdentity.name()).isEqualTo(" nan ");
    }

    @Test
    void repeatedRichProfileDoesNotSaveDuplicateMastersOrAssociations() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");

        Perfume first = service.importProfile(profile);
        Perfume second = service.importProfile(profile);

        assertThat(second).isSameAs(first);
        assertThat(brands).hasSize(1);
        assertThat(perfumes).hasSize(1);
        assertThat(perfumers).hasSize(2);
        assertThat(notes).hasSize(6);
        assertThat(accords).hasSize(3);
        assertThat(perfumerRows).hasSize(2);
        assertThat(noteRows).hasSize(6);
        assertThat(accordRows).hasSize(3);
        verifySaveCounts(1, 1, 2, 2, 6, 6, 3, 3);
    }

    @Test
    void reusesExistingMastersWithoutRefreshingTheirMetadata() throws IOException {
        Brand brand = rememberBrand(new Brand("Existing House Name", "creed"));
        Perfumer perfumer = rememberPerfumer(new Perfumer("p138", "Existing Perfumer Name", "Old Company",
                "Old Biography", 7L));
        Note note = rememberNote(new Note("Pineapple"));
        Accord accord = rememberAccord(new Accord("fruity"));

        Perfume imported = service.importProfile(readProfile("rich-profile.json"));

        assertThat(imported.getBrand()).isSameAs(brand);
        assertThat(brand.getName()).isEqualTo("Existing House Name");
        assertThat(perfumerRows.get(0).getPerfumer()).isSameAs(perfumer);
        assertThat(perfumer.getName()).isEqualTo("Existing Perfumer Name");
        assertThat(perfumer.getCompany()).isEqualTo("Old Company");
        assertThat(perfumer.getBiography()).isEqualTo("Old Biography");
        assertThat(perfumer.getPerfumesCount()).isEqualTo(7L);
        assertThat(noteRows.get(0).getNote()).isSameAs(note);
        assertThat(accordRows.get(0).getAccord()).isSameAs(accord);
        verifySaveCounts(0, 1, 1, 2, 5, 6, 2, 3);
    }

    @Test
    void laterProfileAddsMissingChildrenWithoutRefreshingOrRemovingExistingRows() throws IOException {
        ScentRevFragranceProfileResponse original = readProfile("rich-profile.json");
        Perfume first = service.importProfile(original);
        ScentRevIdentity changedIdentity = new ScentRevIdentity("Changed Name", original.identity().publicId(),
                "Changed Brand", original.identity().brandSlug(), original.identity().fragranceSlug(),
                "Changed Description", 2026, 99L, null, null);
        ScentRevPerfumer changedPerfumer = new ScentRevPerfumer("Changed Perfumer", "Changed Company",
                "Changed Biography", "p138", 999L, null);
        ScentRevAccords changedAccords = accordList(List.of(new ScentRevAccords.Accord("woody", 1,
                new BigDecimal("0.01")), new ScentRevAccords.Accord("green", 44, new BigDecimal("0.44"))));

        Perfume second = service.importProfile(profile(changedIdentity, List.of(changedPerfumer),
                notePyramid(List.of("Bergamot", "Pineapple", "Lemon"), null, null), changedAccords));

        assertThat(second).isSameAs(first);
        assertThat(second.getName()).isEqualTo("Aventus");
        assertThat(second.getDescription()).isEqualTo(original.identity().description());
        assertThat(second.getReleaseYear()).isEqualTo(2010);
        assertThat(second.getReviewsCount()).isEqualTo(26169L);
        assertThat(second.getBrand().getName()).isEqualTo("Creed");
        assertThat(perfumers.get("p138").getName()).isEqualTo("Olivier Creed");
        assertThat(perfumers.get("p138").getCompany()).isEqualTo("Creed");
        assertThat(perfumers.get("p138").getBiography()).isEqualTo(original.perfumers().get(0).biography());
        assertThat(perfumers.get("p138").getPerfumesCount()).isEqualTo(120L);
        assertThat(perfumerRows).hasSize(2);
        assertThat(noteRows).hasSize(7);
        assertThat(noteRows.get(0).getPosition()).isZero();
        assertThat(noteRows.get(1).getPosition()).isEqualTo(1);
        assertThat(noteRows.get(6).getNote().getName()).isEqualTo("Lemon");
        assertThat(noteRows.get(6).getPosition()).isEqualTo(2);
        assertThat(accordRows).hasSize(4);
        assertThat(accordRows.get(2).getPercentage()).isEqualTo(67);
        assertThat(accordRows.get(2).getScore()).isEqualTo(new BigDecimal("0.67"));
        assertThat(accordRows.get(2).getPosition()).isEqualTo(2);
        assertThat(accordRows.get(3).getAccord().getName()).isEqualTo("green");
        verifySaveCounts(1, 1, 2, 2, 7, 7, 4, 4);
    }

    @Test
    void failsBeforeWritesWhenPublicIdAndSlugResolveToDifferentRows() {
        Brand brand = rememberBrand(new Brand("House", "example-house"));
        rememberPerfume(new Perfume("profile-id", "another-slug", "First", null, null, null, brand));
        rememberPerfume(new Perfume("other-id", "example-fragrance", "Second", null, null, null, brand));

        assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(profile(identity(), null, null, null)))
                .withMessageContaining("identifier conflict").withMessageContaining("profile-id")
                .withMessageContaining("example-fragrance");

        verifyOnlyPerfumeLookupsOccurred();
    }

    @Test
    void rejectsSlugOnlyMatchWithDifferentPublicId() {
        Brand brand = rememberBrand(new Brand("House", "example-house"));
        rememberPerfume(new Perfume("other-id", "example-fragrance", "Existing", null, null, null, brand));

        assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(profile(identity(), null, null, null)))
                .withMessageContaining("identifier conflict");

        verifyOnlyPerfumeLookupsOccurred();
    }

    @Test
    void reusesPublicIdMatchWithUnusedIncomingSlugAndKeepsStoredBrand() {
        Brand brand = rememberBrand(new Brand("Stored House", "stored-house"));
        Perfume existing = rememberPerfume(new Perfume("profile-id", "stored-slug", "Stored Name", null,
                null, null, brand));

        Perfume imported = service.importProfile(profile(identity(), null, null, null));

        assertThat(imported).isSameAs(existing);
        assertThat(imported.getFragranceSlug()).isEqualTo("stored-slug");
        assertThat(imported.getBrand()).isSameAs(brand);
        verifyOnlyPerfumeLookupsOccurred();
    }

    @Test
    void acceptsSlugFallbackWhenStoredPublicIdMatches() {
        Brand brand = rememberBrand(new Brand("House", "example-house"));
        Perfume existing = rememberPerfume(new Perfume("profile-id", "example-fragrance", "Existing", null,
                null, null, brand));
        when(perfumeRepository.findByScentrevPublicId("profile-id")).thenReturn(Optional.empty());

        assertThat(service.importProfile(profile(identity(), null, null, null))).isSameAs(existing);
        verifyOnlyPerfumeLookupsOccurred();
    }

    @Test
    void comparesPersistentIdsRatherThanObjectIdentityForBothIdentifierMatches() {
        Brand brand = rememberBrand(new Brand("House", "example-house"));
        Perfume existing = rememberPerfume(new Perfume("profile-id", "example-fragrance", "Existing", null,
                null, null, brand));
        Perfume anotherInstance = spy(new Perfume("profile-id", "example-fragrance", "Existing", null,
                null, null, brand));
        Long existingId = existing.getId();
        when(anotherInstance.getId()).thenReturn(existingId);
        when(perfumeRepository.findByFragranceSlug("example-fragrance")).thenReturn(Optional.of(anotherInstance));

        assertThat(service.importProfile(profile(identity(), null, null, null))).isSameAs(existing);
        verifyOnlyPerfumeLookupsOccurred();
    }

    @Test
    void rejectsMissingProfileOrIdentityBeforeAnyRepositoryInteraction() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(null))
                .withMessageContaining("profile");
        assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(profile(null, null, null, null)))
                .withMessageContaining("identity");
        verifyNoRepositoryInteractions();
    }

    @Test
    void rejectsEachNullOrBlankRequiredIdentityFieldBeforeAnyRepositoryInteraction() {
        String[] paths = {"identity.publicId", "identity.fragranceSlug", "identity.name", "identity.brandSlug",
                "identity.brandName"};
        for (int field = 0; field < paths.length; field++) {
            for (String invalid : Arrays.asList(null, " \t ")) {
                String[] values = {"profile-id", "example-fragrance", "Example", "example-house", "Example House"};
                values[field] = invalid;
                ScentRevIdentity identity = new ScentRevIdentity(values[2], values[0], values[4], values[3], values[1],
                        null, null, null, null, null);
                assertThatIllegalArgumentException()
                        .isThrownBy(() -> service.importProfile(profile(identity, null, null, null)))
                        .withMessageContaining(paths[field]);
            }
        }
        verifyNoRepositoryInteractions();
    }

    @Test
    void rejectsInvalidPerfumerEntriesBeforeAnyWrites() {
        List<ScentRevPerfumer> invalidEntries = Arrays.asList(null,
                new ScentRevPerfumer("Perfumer", null, null, null, null, null),
                new ScentRevPerfumer("Perfumer", null, null, " ", null, null),
                new ScentRevPerfumer(null, null, null, "p1", null, null),
                new ScentRevPerfumer(" ", null, null, "p1", null, null));
        ScentRevPerfumer valid = new ScentRevPerfumer("Valid Perfumer", null, null, "valid-id", null, null);
        for (ScentRevPerfumer invalid : invalidEntries) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(
                    profile(identity(), Arrays.asList(valid, invalid), null, null)))
                    .withMessageContaining("perfumers[1]");
        }
        verifyNoRepositoryInteractions();
    }

    @Test
    void rejectsNullOrBlankAccordEntriesBeforeAnyWrites() {
        List<ScentRevAccords.Accord> invalidEntries = Arrays.asList(null,
                new ScentRevAccords.Accord(null, 10, null), new ScentRevAccords.Accord(" \t ", 10, null));
        for (ScentRevAccords.Accord invalid : invalidEntries) {
            assertThatIllegalArgumentException().isThrownBy(() -> service.importProfile(profile(identity(), null,
                    null, accordList(Arrays.asList(new ScentRevAccords.Accord("valid", 80, null), invalid)))))
                    .withMessageContaining("accords.accords[1]");
        }
        verifyNoRepositoryInteractions();
    }

    @Test
    void skipsInvalidNotesPreservesSourcePositionsAndDoesNotMergeAliasesOrLayers() {
        List<String> top = Arrays.asList(null, " \t ", " Oakmoss ", "Oak Moss", "oak moss", "Oakmoss");

        service.importProfile(profile(identity(), null, notePyramid(top, null, List.of("Oakmoss")), null));

        assertThat(notes.keySet()).containsExactly("Oakmoss", "Oak Moss", "oak moss");
        assertThat(noteRows).extracting(row -> row.getNote().getName(), PerfumeNote::getLayer, PerfumeNote::getPosition)
                .containsExactly(tuple("Oakmoss", NoteLayer.TOP, 2), tuple("Oak Moss", NoteLayer.TOP, 3),
                        tuple("oak moss", NoteLayer.TOP, 4), tuple("Oakmoss", NoteLayer.BASE, 0));
        assertThat(noteRows.get(0).getNote()).isSameAs(noteRows.get(3).getNote());
        assertThat(top.get(2)).isEqualTo(" Oakmoss ");
        verify(noteRepository, times(3)).save(any(Note.class));
        verify(perfumeNoteRepository, times(4)).save(any(PerfumeNote.class));
    }

    @Test
    void preservesIndependentNullableAccordMetricsAndKeepsFirstDuplicatePosition() {
        BigDecimal exactScore = new BigDecimal("0.123456789012345678901234567890");
        ScentRevAccords source = accordList(List.of(new ScentRevAccords.Accord(" Green ", 68, exactScore),
                new ScentRevAccords.Accord("Green", 1, BigDecimal.ONE),
                new ScentRevAccords.Accord("nan", null, null)));

        service.importProfile(profile(identity(), null, null, source));

        assertThat(accords.keySet()).containsExactly("Green", "nan");
        assertThat(accordRows).hasSize(2);
        assertThat(accordRows.get(0).getPercentage()).isEqualTo(68);
        assertThat(accordRows.get(0).getScore()).isEqualTo(exactScore);
        assertThat(accordRows.get(0).getPosition()).isZero();
        assertThat(accordRows.get(1).getPercentage()).isNull();
        assertThat(accordRows.get(1).getScore()).isNull();
        assertThat(accordRows.get(1).getPosition()).isEqualTo(2);
        assertThat(source.accords().get(0).name()).isEqualTo(" Green ");
        verify(accordRepository, times(2)).save(any(Accord.class));
        verify(perfumeAccordRepository, times(2)).save(any(PerfumeAccord.class));
    }

    private ScentRevFragranceProfileResponse readProfile(String fileName) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/scentrev/" + fileName)) {
            assertThat(input).isNotNull();
            return new ObjectMapper().readValue(input, ScentRevFragranceProfileResponse.class);
        }
    }

    private ScentRevIdentity identity() {
        return new ScentRevIdentity("Example", "profile-id", "Example House", "example-house", "example-fragrance",
                null, null, null, null, null);
    }

    private ScentRevFragranceProfileResponse profile(ScentRevIdentity identity, List<ScentRevPerfumer> perfumers,
                                                    ScentRevNotePyramid notes, ScentRevAccords accords) {
        return new ScentRevFragranceProfileResponse(identity, accords, perfumers, notes, null, null, null, null);
    }

    private ScentRevNotePyramid notePyramid(List<String> top, List<String> middle, List<String> base) {
        return new ScentRevNotePyramid(top, middle, base, null, null, null, null);
    }

    private ScentRevAccords accordList(List<ScentRevAccords.Accord> accords) {
        return new ScentRevAccords(accords, null, null, null, null);
    }

    // Spies simulate generated IDs through getters only; entity fields/mappings are untouched.
    private Brand rememberBrand(Brand entity) {
        Brand saved = spy(entity);
        when(saved.getId()).thenReturn(nextId++);
        brands.put(saved.getBrandSlug(), saved);
        return saved;
    }

    private Perfume rememberPerfume(Perfume entity) {
        Perfume saved = spy(entity);
        when(saved.getId()).thenReturn(nextId++);
        perfumes.put(saved.getScentrevPublicId(), saved);
        return saved;
    }

    private Perfumer rememberPerfumer(Perfumer entity) {
        Perfumer saved = spy(entity);
        when(saved.getId()).thenReturn(nextId++);
        perfumers.put(saved.getScentrevPerfumerId(), saved);
        return saved;
    }

    private Note rememberNote(Note entity) {
        Note saved = spy(entity);
        when(saved.getId()).thenReturn(nextId++);
        notes.put(saved.getName(), saved);
        return saved;
    }

    private Accord rememberAccord(Accord entity) {
        Accord saved = spy(entity);
        when(saved.getId()).thenReturn(nextId++);
        accords.put(saved.getName(), saved);
        return saved;
    }

    private void verifySaveCounts(int brandCount, int perfumeCount, int perfumerCount, int perfumerRowCount,
                                  int noteCount, int noteRowCount, int accordCount, int accordRowCount) {
        verify(brandRepository, times(brandCount)).save(any(Brand.class));
        verify(perfumeRepository, times(perfumeCount)).save(any(Perfume.class));
        verify(perfumerRepository, times(perfumerCount)).save(any(Perfumer.class));
        verify(perfumePerfumerRepository, times(perfumerRowCount)).save(any(PerfumePerfumer.class));
        verify(noteRepository, times(noteCount)).save(any(Note.class));
        verify(perfumeNoteRepository, times(noteRowCount)).save(any(PerfumeNote.class));
        verify(accordRepository, times(accordCount)).save(any(Accord.class));
        verify(perfumeAccordRepository, times(accordRowCount)).save(any(PerfumeAccord.class));
    }

    private void verifyNoChildRepositoryInteractions() {
        verifyNoInteractions(perfumerRepository, perfumePerfumerRepository, noteRepository,
                perfumeNoteRepository, accordRepository, perfumeAccordRepository);
    }

    private void verifyOnlyPerfumeLookupsOccurred() {
        verify(perfumeRepository, never()).save(any(Perfume.class));
        verifyNoInteractions(brandRepository);
        verifyNoChildRepositoryInteractions();
    }

    private void verifyNoRepositoryInteractions() {
        verifyNoInteractions(brandRepository, perfumeRepository);
        verifyNoChildRepositoryInteractions();
    }
}
