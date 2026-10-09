package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfume.domain.*;
import com.perfume.repository.*;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;

/** Synthetic schema examples and in-memory repository mocks; no provider/JDBC connection. */
class ScentRevProfileDataServiceTests {
    @Test
    void normalizesAllKnownSectionsAndPreservesUnknownPayloadFieldsAndExactDecimals() throws IOException {
        var store = new FullProfileTestStore();
        var profile = fullProfile();
        assertThat(store.data.save(store.perfume, profile)).isTrue();
        assertThat(store.metrics).containsKeys("identity.rating", "identity.gender", "performance.longevity", "performance.sillage",
                "performance.projection", "performance.season.by_season.spring", "performance.time_of_day", "appreciation", "price_value");
        assertThat(store.metrics.get("identity.rating").getScore()).isEqualByComparingTo("4.381234567890123456789");
        assertThat(store.metrics.get("identity.rating").getNRecords()).isEqualTo(20000L);
        assertThat(store.metrics.get("performance.projection").getDerivedFrom()).isEqualTo("longevity_sillage");
        assertThat(store.metrics.get("performance.season.primary").getLabel()).isEqualTo("spring");
        assertThat(store.metrics.get("performance.season.by_season.spring").getScale()).isEqualTo("relative_suitability");
        assertThat(store.opinions).hasSize(2);
        assertThat(store.opinions).extracting(PerfumeOpinion::getKind).containsExactly(PerfumeOpinion.Kind.PRO, PerfumeOpinion.Kind.CON);
        assertThat(store.similarities).singleElement().satisfies(row -> {
            assertThat(row.getRelatedPublicId()).isEqualTo("fragrance-related");
            assertThat(row.getLikeRatio()).isEqualByComparingTo("0.81234567890123456789");
        });
        assertThat(store.flatNotes).hasSize(2);
        assertThat(store.perfume.getImageUrl()).isEqualTo("https://example.invalid/provider-image.jpg");
        assertThat(store.payloads).singleElement().satisfies(row -> {
            assertThat(row.getPayload().path("future_section").path("provider_only_field").asText()).isEqualTo("retained exactly");
            assertThat(row.getPayload()).isEqualTo(profile.rawResponse());
            assertThat(row.getReceivedAt()).isNotNull();
        });
    }

    @Test
    void identicalRerunsAndObjectKeyOrderChangesInsertNoDuplicateRows() throws IOException {
        var store = new FullProfileTestStore(); var profile = fullProfile();
        store.data.save(store.perfume, profile);
        var ids = store.metrics.values().stream().map(PerfumeMetric::getId).toList();
        assertThat(store.data.save(store.perfume, profile)).isFalse();
        var reverse = store.mapper.createObjectNode();
        var keys = new ArrayList<String>(); profile.rawResponse().fieldNames().forEachRemaining(keys::add);
        java.util.Collections.reverse(keys); keys.forEach(key -> reverse.set(key, profile.rawResponse().get(key)));
        assertThat(store.data.save(store.perfume, profile.withRawResponse(reverse))).isFalse();
        assertThat(store.metrics.values()).extracting(PerfumeMetric::getId).containsExactlyElementsOf(ids);
        assertThat(store.opinions).hasSize(2); assertThat(store.similarities).hasSize(1); assertThat(store.flatNotes).hasSize(2);
        assertThat(store.payloads).hasSize(1);
        verify(store.metricRepo, times(store.metrics.size())).save(any());
        verify(store.payloadRepo, times(1)).save(any());
    }

    @Test
    void sparseNullResponsesNeverEraseKnownMetricsOpinionsSimilaritiesOrImages() throws IOException {
        var store = new FullProfileTestStore(); store.data.save(store.perfume, fullProfile());
        var root = store.mapper.createObjectNode();
        root.set("identity", fullProfile().rawResponse().get("identity").deepCopy());
        ((ObjectNode) root.path("identity")).putNull("image_url");
        root.putObject("performance").putObject("longevity").putNull("score").putNull("n_records");
        root.putObject("pros_cons").putArray("pros"); root.putObject("reminds_of").putArray("reminds_of");
        var sparse = store.mapper.treeToValue(root, ScentRevFragranceProfileResponse.class).withRawResponse(root);
        store.data.save(store.perfume, sparse);
        assertThat(store.metrics.get("performance.longevity").getScore()).isEqualByComparingTo("0.83");
        assertThat(store.metrics.get("performance.longevity").getNRecords()).isEqualTo(1432L);
        assertThat(store.opinions).hasSize(2); assertThat(store.similarities).hasSize(1); assertThat(store.flatNotes).hasSize(2);
        assertThat(store.perfume.getImageUrl()).isEqualTo("https://example.invalid/provider-image.jpg");
        assertThat(store.payloads).hasSize(2);
        assertThat(store.payloads.get(0).getPayload().path("future_section").isMissingNode()).isFalse();
        assertThat(store.payloads.get(1).getPayload().path("performance").path("longevity").path("score").isNull()).isTrue();
    }

    @Test
    void changedMetricsUpdateExistingRowsAndKeepHistoricalSnapshots() throws IOException {
        var store = new FullProfileTestStore(); var profile = fullProfile(); store.data.save(store.perfume, profile);
        var metric = store.metrics.get("performance.longevity"); Long originalId = metric.getId();
        var root = (ObjectNode) profile.rawResponse().deepCopy();
        ((ObjectNode) root.path("performance").path("longevity")).put("score", new java.math.BigDecimal("0.91234567890123456789"));
        assertThat(store.data.save(store.perfume, profile.withRawResponse(root))).isTrue();
        assertThat(metric.getId()).isEqualTo(originalId);
        assertThat(metric.getScore()).isEqualByComparingTo("0.91234567890123456789");
        assertThat(metric.getReliability()).isEqualTo("high");
        assertThat(store.payloads).hasSize(2);
        assertThat(store.payloads.get(0).getPayload().path("performance").path("longevity").path("score").decimalValue()).isEqualByComparingTo("0.83");
    }

    @Test
    void similaritySlugFallbackUpgradesToProviderIdWithoutCreatingAnotherReference() throws IOException {
        var store = new FullProfileTestStore(); var profile = fullProfile();
        var root = (ObjectNode) profile.rawResponse().deepCopy();
        ((ObjectNode) root.path("reminds_of").path("reminds_of").get(0)).remove("public_id");
        store.data.save(store.perfume, profile.withRawResponse(root));
        var row = store.similarities.get(0); Long originalId = row.getId();
        store.data.save(store.perfume, profile);
        assertThat(store.similarities).hasSize(1);
        assertThat(row.getId()).isEqualTo(originalId);
        assertThat(row.getReferenceKey()).isEqualTo("id:fragrance-related");
        verify(store.similarityRepo, times(1)).save(any());
    }

    @Test
    void invalidSectionIdentityOrNumericShapeFailsBeforeAnyRepositoryInteraction() throws IOException {
        var store = new FullProfileTestStore(); var profile = fullProfile();
        var wrongIdentity = (ObjectNode) profile.rawResponse().deepCopy();
        ((ObjectNode) wrongIdentity.path("performance")).put("public_id", "unrelated-id");
        assertThatThrownBy(() -> store.data.save(store.perfume, profile.withRawResponse(wrongIdentity)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity conflicts");
        var wrongNumber = (ObjectNode) profile.rawResponse().deepCopy();
        ((ObjectNode) wrongNumber.path("performance").path("longevity")).put("n_records", -1);
        assertThatThrownBy(() -> store.data.save(store.perfume, profile.withRawResponse(wrongNumber)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BIGINT");
        verifyNoInteractions(store.metricRepo, store.opinionRepo, store.similarityRepo, store.payloadRepo, store.noteRepo, store.flatRepo);
    }

    @Test
    void wearFallbackNeedsOnlyMissingInformationAndItsOriginalResponseIsStoredSeparately() throws IOException {
        var store = new FullProfileTestStore(); var full = fullProfile();
        assertThat(ScentRevProfileDataService.needsWearSummary(full)).isFalse();
        var root = (ObjectNode) full.rawResponse().deepCopy(); root.remove("performance");
        var sparse = full.withRawResponse(root);
        assertThat(ScentRevProfileDataService.needsWearSummary(sparse)).isTrue();
        var wear = (ObjectNode) full.rawResponse().path("performance").deepCopy();
        var supplemented = sparse.withWearSummary(wear);
        assertThat(ScentRevProfileDataService.needsWearSummary(supplemented)).isFalse();
        store.data.save(store.perfume, supplemented);
        assertThat(store.metrics).containsKeys("wear_summary.longevity", "wear_summary.season.by_season.summer", "wear_summary.time_of_day");
        assertThat(store.payloads).extracting(PerfumeProviderPayload::getToolName).containsExactly("get_fragrance_profile", "get_wear_summary");
        wear.put("public_id", "wrong-id");
        assertThatThrownBy(() -> ScentRevProfileDataService.validateWearSummary(full, wear)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void seededPageRerunEnrichesExistingPerfumeThenBecomesUnchangedAndPreservesOptionalParentFields() throws IOException {
        var store = new FullProfileTestStore();
        var brandRepo = mock(BrandRepository.class); var perfumeRepo = mock(PerfumeRepository.class);
        when(brandRepo.findById(2L)).thenReturn(Optional.of(store.perfume.getBrand()));
        when(perfumeRepo.findByScentrevPublicId("fragrance-aventus")).thenReturn(Optional.of(store.perfume));
        when(perfumeRepo.findByFragranceSlug("creed-aventus")).thenReturn(Optional.of(store.perfume));
        var service = new ScentRevPhase1ImportService(brandRepo, perfumeRepo, mock(PerfumerRepository.class),
                mock(PerfumePerfumerRepository.class), store.noteRepo, mock(PerfumeNoteRepository.class),
                mock(AccordRepository.class), mock(PerfumeAccordRepository.class), store.data);
        var root = (ObjectNode) fullProfile().rawResponse().deepCopy();
        root.remove(List.of("perfumers", "note_pyramid", "accords"));
        var profile = store.mapper.treeToValue(root, ScentRevFragranceProfileResponse.class).withRawResponse(root);
        assertThat(service.importPageForSeededBrand(2L, "creed", List.of(profile)).perfumesUpdated()).isEqualTo(1);
        assertThat(service.importPageForSeededBrand(2L, "creed", List.of(profile)).perfumesUnchanged()).isEqualTo(1);
        var sparse = new ScentRevFragranceProfileResponse(new ScentRevIdentity("Aventus", "fragrance-aventus", "Creed", "creed",
                "creed-aventus", null, null, null, null, null), null, List.of(), null, null, null, null, null);
        assertThat(service.importPageForSeededBrand(2L, "creed", List.of(sparse)).perfumesUnchanged()).isEqualTo(1);
        assertThat(store.perfume.getReleaseYear()).isEqualTo(2010);
        assertThat(store.perfume.getDescription()).isNotBlank();
        assertThat(store.perfume.getReviewsCount()).isEqualTo(26169L);
        verify(perfumeRepo, never()).save(any()); verify(brandRepo, never()).save(any());
    }

    static ScentRevFragranceProfileResponse fullProfile() throws IOException {
        var mapper = FullProfileTestStore.mapper();
        ObjectNode root;
        try (var input = ScentRevProfileDataServiceTests.class.getResourceAsStream("/scentrev/rich-profile.json")) {
            root = (ObjectNode) mapper.readTree(input);
        }
        root.putObject("appreciation").put("score", new java.math.BigDecimal("0.827")).put("n_records", 4294967297L).put("category", "beloved");
        ((ObjectNode) root.path("identity")).put("image_url", "https://example.invalid/provider-image.jpg");
        root.putObject("future_section").put("provider_only_field", "retained exactly");
        root.putArray("notes").add("Musk").add("Musk").add("Rose");
        return mapper.treeToValue(root, ScentRevFragranceProfileResponse.class).withRawResponse(root);
    }
}

/** In-memory repository boundaries for the real enrichment service; no Spring application or JDBC. */
class FullProfileTestStore {
    final ObjectMapper mapper = mapper();
    final PerfumeMetricRepository metricRepo = mock(PerfumeMetricRepository.class);
    final PerfumeOpinionRepository opinionRepo = mock(PerfumeOpinionRepository.class);
    final PerfumeSimilarityRepository similarityRepo = mock(PerfumeSimilarityRepository.class);
    final PerfumeProviderPayloadRepository payloadRepo = mock(PerfumeProviderPayloadRepository.class);
    final NoteRepository noteRepo = mock(NoteRepository.class);
    final PerfumeUnlayeredNoteRepository flatRepo = mock(PerfumeUnlayeredNoteRepository.class);
    final Map<String, PerfumeMetric> metrics = new LinkedHashMap<>();
    final List<PerfumeOpinion> opinions = new ArrayList<>();
    final List<PerfumeSimilarity> similarities = new ArrayList<>();
    final List<PerfumeProviderPayload> payloads = new ArrayList<>();
    final Map<String, Note> notes = new LinkedHashMap<>();
    final List<PerfumeUnlayeredNote> flatNotes = new ArrayList<>();
    final ScentRevProfileDataService data = new ScentRevProfileDataService(mapper, metricRepo, opinionRepo, similarityRepo, payloadRepo, noteRepo, flatRepo);
    final Perfume perfume;
    private final AtomicLong ids = new AtomicLong(10);
    FullProfileTestStore() {
        var brand = new Brand("Creed", "creed"); ReflectionTestUtils.setField(brand, "id", 2L);
        perfume = new Perfume("fragrance-aventus", "creed-aventus", "Aventus", 2010, "Original description", 26169L, brand);
        ReflectionTestUtils.setField(perfume, "id", 1L);
        when(metricRepo.findByPerfume_IdAndMetricKey(anyLong(), anyString())).thenAnswer(call -> Optional.ofNullable(metrics.get(call.getArgument(1))));
        when(metricRepo.save(any())).thenAnswer(call -> { PerfumeMetric row = identify(call.getArgument(0)); metrics.put(row.getMetricKey(), row); return row; });
        when(opinionRepo.findByPerfume_IdAndKindAndTextHash(anyLong(), any(), anyString())).thenAnswer(call -> opinions.stream()
                .filter(row -> row.getKind() == call.getArgument(1) && row.getTextHash().equals(call.getArgument(2))).findFirst());
        when(opinionRepo.save(any())).thenAnswer(call -> { PerfumeOpinion row = identify(call.getArgument(0)); opinions.add(row); return row; });
        when(similarityRepo.findByPerfume_IdAndReferenceKey(anyLong(), anyString())).thenAnswer(call -> similarities.stream()
                .filter(row -> row.getReferenceKey().equals(call.getArgument(1))).findFirst());
        when(similarityRepo.findByPerfume_IdAndRelatedFragranceSlug(anyLong(), anyString())).thenAnswer(call -> similarities.stream()
                .filter(row -> java.util.Objects.equals(row.getRelatedFragranceSlug(), call.getArgument(1))).findFirst());
        when(similarityRepo.save(any())).thenAnswer(call -> { PerfumeSimilarity row = identify(call.getArgument(0)); similarities.add(row); return row; });
        when(payloadRepo.existsByPerfume_IdAndToolNameAndPayloadHash(anyLong(), anyString(), anyString())).thenAnswer(call -> payloads.stream()
                .anyMatch(row -> row.getToolName().equals(call.getArgument(1)) && row.getPayloadHash().equals(call.getArgument(2))));
        when(payloadRepo.save(any())).thenAnswer(call -> { PerfumeProviderPayload row = identify(call.getArgument(0)); payloads.add(row); return row; });
        when(noteRepo.findByName(anyString())).thenAnswer(call -> Optional.ofNullable(notes.get(call.getArgument(0))));
        when(noteRepo.save(any())).thenAnswer(call -> { Note row = identify(call.getArgument(0)); notes.put(row.getName(), row); return row; });
        when(flatRepo.findByPerfume_IdAndNote_Id(anyLong(), anyLong())).thenAnswer(call -> flatNotes.stream()
                .filter(row -> row.getNote().getId().equals(call.getArgument(1))).findFirst());
        when(flatRepo.save(any())).thenAnswer(call -> { PerfumeUnlayeredNote row = identify(call.getArgument(0)); flatNotes.add(row); return row; });
    }
    private <T> T identify(T row) { ReflectionTestUtils.setField(row, "id", ids.incrementAndGet()); return row; }
    static ObjectMapper mapper() { return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS); }
}
