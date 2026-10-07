package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.perfume.domain.NoteLayer;
import com.perfume.domain.Perfume;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.service.ScentRevPhase1ImportService;

/**
 * Opt-in, real MCP/PostgreSQL test for Aventus only. Imports commit through the
 * existing service and remain in the configured database; there is no cleanup.
 * Schema validation must succeed before any profile request or persistence.
 */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.db-live-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevAventusDatabaseSmokeTests {

    private static final String SLUG = "creed-aventus";
    private static final String PUBLIC_ID = "312329ca-0ad9-4a79-a586-2a4b815c18f7";
    private static final String BRAND_SLUG = "creed";

    @Autowired
    private ScentRevMcpClient client;
    @Autowired
    private ScentRevPhase1ImportService importer;
    @Autowired
    private PerfumeRepository perfumeRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    // Deliberately no test @Transactional: the service's two imports commit normally.
    @Test
    void commitsOnlyAventusAndSecondImportCreatesNoDuplicates() {
        var firstProfile = client.getFragranceProfile(SLUG);
        ExpectedAssociations expected = validateLiveProfile(firstProfile);
        verifyExistingIdentifiersBeforeWriting();

        Long perfumeId = importer.importProfile(firstProfile).getId();
        StoredAssociations first = readOnly(() -> reloadAndVerify(firstProfile, expected, perfumeId));

        // Exactly one additional profile request, solely for explicit idempotency verification.
        var secondProfile = client.getFragranceProfile(SLUG);
        ExpectedAssociations secondExpected = validateLiveProfile(secondProfile);
        assertThat(secondExpected.perfumerIds()).as("Live perfumer identities changed between requests")
                .isEqualTo(expected.perfumerIds());
        assertThat(noteKeys(secondExpected.notes())).as("Live note identities changed between requests")
                .isEqualTo(noteKeys(expected.notes()));
        assertThat(accordNames(secondExpected.accords())).as("Live accord identities changed between requests")
                .isEqualTo(accordNames(expected.accords()));
        verifyExistingIdentifiersBeforeWriting();

        Long secondPerfumeId = importer.importProfile(secondProfile).getId();
        assertThat(secondPerfumeId).as("Second import must reuse the canonical Aventus row").isEqualTo(perfumeId);
        StoredAssociations second = readOnly(() -> reloadAndVerify(firstProfile, expected, perfumeId));
        assertThat(second.counts()).as("Aventus counts in all eight Phase 1 tables must remain unchanged")
                .isEqualTo(first.counts());
        assertThat(second).as("Existing IDs, associations, positions and accord metrics must be reused")
                .isEqualTo(first);
    }

    private ExpectedAssociations validateLiveProfile(ScentRevFragranceProfileResponse profile) {
        assertThat(profile).as("Live Phase 1 profile").isNotNull();
        var identity = profile.identity();
        assertThat(identity).as("Live identity").isNotNull();
        assertThat(identity.publicId()).as("Refuse to import any fragrance other than canonical Aventus")
                .isEqualTo(PUBLIC_ID);
        assertThat(identity.name()).isEqualTo("Aventus");
        assertThat(identity.fragranceSlug()).isEqualTo(SLUG);
        assertThat(identity.brandName()).isEqualTo("Creed");
        assertThat(identity.brandSlug()).isEqualTo(BRAND_SLUG);
        if (identity.releaseYear() != null) {
            assertThat(identity.releaseYear()).isEqualTo(2010);
        }
        assertThat(profile.perfumers()).as("Requested perfumers section").isNotNull();
        assertThat(profile.notePyramid()).as("Requested notes section (note_pyramid)").isNotNull();
        assertThat(profile.accords()).as("Requested accords section").isNotNull();
        assertThat(profile.accords().accords()).as("Live accord entries").isNotNull();

        Set<String> perfumerIds = new HashSet<>();
        for (var perfumer : profile.perfumers()) {
            assertThat(perfumer).as("Live perfumer entry").isNotNull();
            assertThat(perfumer.perfumerId()).as("Live perfumer ID").isNotBlank();
            assertThat(perfumer.name()).as("Live perfumer name").isNotBlank();
            assertThat(perfumerIds.add(perfumer.perfumerId()))
                    .as("Repeated live perfumer ID cannot represent two unique associations").isTrue();
        }
        List<ExpectedNote> notes = new ArrayList<>();
        addNotes(notes, profile.notePyramid().top(), NoteLayer.TOP);
        addNotes(notes, profile.notePyramid().middle(), NoteLayer.MIDDLE);
        addNotes(notes, profile.notePyramid().base(), NoteLayer.BASE);
        assertThat(noteKeys(notes)).as("Repeated live note name within a layer cannot preserve both positions")
                .hasSize(notes.size());

        List<ExpectedAccord> accords = new ArrayList<>();
        for (int position = 0; position < profile.accords().accords().size(); position++) {
            var accord = profile.accords().accords().get(position);
            assertThat(accord).as("Live accord entry").isNotNull();
            assertThat(accord.name()).as("Live accord name").isNotBlank();
            accords.add(new ExpectedAccord(accord.name().strip(), accord.percentage(), accord.score(), position));
        }
        assertThat(accordNames(accords)).as("Repeated live accord name cannot represent two unique associations")
                .hasSize(accords.size());
        return new ExpectedAssociations(Set.copyOf(perfumerIds), List.copyOf(notes), List.copyOf(accords));
    }

    private static void addNotes(List<ExpectedNote> result, List<String> source, NoteLayer layer) {
        if (source == null) {
            return;
        }
        for (int position = 0; position < source.size(); position++) {
            String name = source.get(position);
            if (name != null && !name.isBlank()) {
                result.add(new ExpectedNote(name.strip(), layer, position));
            }
        }
    }

    private void verifyExistingIdentifiersBeforeWriting() {
        readOnly(() -> {
            var byPublicId = perfumeRepository.findByScentrevPublicId(PUBLIC_ID);
            var bySlug = perfumeRepository.findByFragranceSlug(SLUG);
            if (byPublicId.isPresent() && bySlug.isPresent()) {
                assertThat(byPublicId.get().getId())
                        .as("Aventus identifier conflict across different rows; no merge or write is permitted")
                        .isEqualTo(bySlug.get().getId());
            }
            byPublicId.ifPresent(this::verifyCanonicalIdentity);
            bySlug.ifPresent(this::verifyCanonicalIdentity);
            return true;
        });
    }

    private void verifyCanonicalIdentity(Perfume perfume) {
        assertThat(perfume.getScentrevPublicId()).as("Existing Aventus public ID conflict").isEqualTo(PUBLIC_ID);
        assertThat(perfume.getFragranceSlug()).as("Existing Aventus slug conflict").isEqualTo(SLUG);
        assertThat(perfume.getName()).isEqualTo("Aventus");
        assertThat(perfume.getBrand()).isNotNull();
        // Called within a read-only transaction so the LAZY brand can be loaded safely.
        assertThat(perfume.getBrand().getName()).isEqualTo("Creed");
        assertThat(perfume.getBrand().getBrandSlug()).isEqualTo(BRAND_SLUG);
    }

    private StoredAssociations reloadAndVerify(ScentRevFragranceProfileResponse profile,
                                               ExpectedAssociations expected, Long perfumeId) {
        Perfume reloaded = perfumeRepository.findByScentrevPublicId(PUBLIC_ID)
                .orElseThrow(() -> new AssertionError("Committed Aventus was not found by its ScentRev public ID"));
        assertThat(reloaded.getId()).isEqualTo(perfumeId);
        verifyCanonicalIdentity(reloaded);
        if (profile.identity().releaseYear() != null) {
            assertThat(reloaded.getReleaseYear()).isEqualTo(2010);
        }

        var perfumers = jdbc.query("""
                select pp.id as link_id, p.id as master_id, p.scentrev_perfumer_id
                from perfume_perfumers pp join perfumers p on p.id = pp.perfumer_id
                where pp.perfume_id = ? order by pp.id
                """, (row, index) -> new StoredPerfumer(row.getLong("link_id"), row.getLong("master_id"),
                        row.getString("scentrev_perfumer_id")), perfumeId);
        var notes = jdbc.query("""
                select pn.id as link_id, n.id as master_id, n.name, pn.layer, pn.position
                from perfume_notes pn join notes n on n.id = pn.note_id
                where pn.perfume_id = ? order by pn.id
                """, (row, index) -> new StoredNote(row.getLong("link_id"), row.getLong("master_id"),
                        row.getString("name"), NoteLayer.valueOf(row.getString("layer")),
                        row.getInt("position")), perfumeId);
        var accords = jdbc.query("""
                select pa.id as link_id, a.id as master_id, a.name, pa.percentage, pa.score, pa.position
                from perfume_accords pa join accords a on a.id = pa.accord_id
                where pa.perfume_id = ? order by pa.id
                """, (row, index) -> new StoredAccord(row.getLong("link_id"), row.getLong("master_id"),
                        row.getString("name"), row.getObject("percentage", Integer.class),
                        row.getBigDecimal("score"), row.getInt("position")), perfumeId);
        Map<String, Long> counts = aventusCounts(perfumeId);

        assertThat(perfumers).hasSize(profile.perfumers().size());
        assertThat(perfumers).extracting(StoredPerfumer::publicId)
                .containsExactlyInAnyOrderElementsOf(expected.perfumerIds());
        assertThat(notes).as("Persisted notes must match live notes; existing links are additive, not refreshed")
                .hasSize(expected.notes().size());
        assertThat(notes).extracting(note -> new ExpectedNote(note.name(), note.layer(), note.position()))
                .containsExactlyInAnyOrderElementsOf(expected.notes());
        assertThat(accords).as("Persisted accords must match live accords; existing metrics are not refreshed")
                .hasSize(expected.accords().size());
        for (ExpectedAccord source : expected.accords()) {
            StoredAccord stored = accords.stream().filter(accord -> accord.name().equals(source.name()))
                    .findFirst().orElseThrow(() -> new AssertionError("Missing live Aventus accord: " + source.name()));
            assertThat(stored.percentage()).as("Percentage for %s", source.name()).isEqualTo(source.percentage());
            assertThat(stored.position()).as("Zero-based position for %s", source.name()).isEqualTo(source.position());
            if (source.score() == null) {
                assertThat(stored.score()).as("Score for %s", source.name()).isNull();
            } else {
                assertThat(stored.score()).as("Score for %s", source.name()).isEqualByComparingTo(source.score());
            }
        }
        assertThat(counts).containsEntry("brands", 1L).containsEntry("perfumes", 1L)
                .containsEntry("perfumers", (long) expected.perfumerIds().size())
                .containsEntry("perfume_perfumers", (long) profile.perfumers().size())
                .containsEntry("notes", expected.notes().stream().map(ExpectedNote::name).distinct().count())
                .containsEntry("perfume_notes", (long) expected.notes().size())
                .containsEntry("accords", (long) expected.accords().size())
                .containsEntry("perfume_accords", (long) expected.accords().size());
        return new StoredAssociations(Map.copyOf(counts), List.copyOf(perfumers), List.copyOf(notes), List.copyOf(accords));
    }

    /** Counts only Aventus and its master keys, including shared notes/perfumers/accords. */
    private Map<String, Long> aventusCounts(Long perfumeId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("brands", count("select count(*) from brands where brand_slug = ?", BRAND_SLUG));
        counts.put("perfumes", count("select count(*) from perfumes where scentrev_public_id = ? or fragrance_slug = ?",
                PUBLIC_ID, SLUG));
        counts.put("perfumers", count("""
                select count(*) from perfumers where scentrev_perfumer_id in (
                    select p.scentrev_perfumer_id from perfumers p
                    join perfume_perfumers pp on pp.perfumer_id = p.id where pp.perfume_id = ?)
                """, perfumeId));
        counts.put("perfume_perfumers", count("select count(*) from perfume_perfumers where perfume_id = ?", perfumeId));
        counts.put("notes", count("""
                select count(*) from notes where name in (
                    select n.name from notes n join perfume_notes pn on pn.note_id = n.id where pn.perfume_id = ?)
                """, perfumeId));
        counts.put("perfume_notes", count("select count(*) from perfume_notes where perfume_id = ?", perfumeId));
        counts.put("accords", count("""
                select count(*) from accords where name in (
                    select a.name from accords a join perfume_accords pa on pa.accord_id = a.id where pa.perfume_id = ?)
                """, perfumeId));
        counts.put("perfume_accords", count("select count(*) from perfume_accords where perfume_id = ?", perfumeId));
        return counts;
    }

    private Long count(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private <T> T readOnly(Supplier<T> query) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        return Objects.requireNonNull(transaction.execute(status -> query.get()));
    }

    private static Set<NoteKey> noteKeys(List<ExpectedNote> notes) {
        return notes.stream().map(note -> new NoteKey(note.name(), note.layer())).collect(Collectors.toSet());
    }

    private static Set<String> accordNames(List<ExpectedAccord> accords) {
        return accords.stream().map(ExpectedAccord::name).collect(Collectors.toSet());
    }

    private record NoteKey(String name, NoteLayer layer) { }
    private record ExpectedNote(String name, NoteLayer layer, int position) { }
    private record ExpectedAccord(String name, Integer percentage, BigDecimal score, int position) { }
    private record ExpectedAssociations(Set<String> perfumerIds, List<ExpectedNote> notes,
                                        List<ExpectedAccord> accords) { }
    private record StoredPerfumer(long linkId, long masterId, String publicId) { }
    private record StoredNote(long linkId, long masterId, String name, NoteLayer layer, int position) { }
    private record StoredAccord(long linkId, long masterId, String name, Integer percentage,
                                BigDecimal score, int position) { }
    private record StoredAssociations(Map<String, Long> counts, List<StoredPerfumer> perfumers,
                                       List<StoredNote> notes, List<StoredAccord> accords) { }
}
