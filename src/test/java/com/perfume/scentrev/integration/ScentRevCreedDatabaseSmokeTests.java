package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import com.perfume.scentrev.service.ScentRevBrandPhase1ImportService;

/**
 * Opt-in, one real Creed import. Successful mapper transactions remain committed,
 * including after failure. No cleanup or second catalog-wide profile pass.
 * Manually rerunning safely delegates to the existing idempotent Phase 1 mapper.
 */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.creed-db-live-test", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCreedDatabaseSmokeTests {

    @Autowired
    private ScentRevBrandPhase1ImportService importer;
    @Autowired
    private JdbcTemplate jdbc;

    // No test @Transactional: each existing mapper invocation commits independently.
    @Test
    void importsOnlyCreedAndVerifiesAllDiscoveredCanonicalRows() {
        List<StoredPerfume> priorCreed = creedRows();
        Map<Long, Map<String, Set<Long>>> priorLinks = new HashMap<>();
        for (var perfume : priorCreed) {
            priorLinks.put(perfume.id(), associationIds(perfume.id()));
        }

        var result = importer.importBrand("creed"); // Exactly one complete profile pass.
        var discovery = result.discovery();
        assertThat(discovery.brandSlug()).isEqualTo("creed");
        assertThat(discovery.uniqueFragranceCount()).isPositive();
        assertThat(result.successfullyProcessedCount()).isEqualTo(discovery.uniqueFragranceCount());
        assertThat(count("select count(*) from brands where brand_slug = ?", "creed")).isEqualTo(1L);

        var stored = new ArrayList<StoredPerfume>();
        for (var fragrance : discovery.fragrances()) {
            // Query by slug alone so a wrongly persisted brand cannot be hidden by a Creed filter.
            var rows = jdbc.query("""
                    select p.id, p.fragrance_slug, p.scentrev_public_id, b.brand_slug
                    from perfumes p left join brands b on b.id = p.brand_id where p.fragrance_slug = ?
                    """, (row, index) -> new StoredPerfume(row.getLong("id"), row.getString("fragrance_slug"),
                            row.getString("scentrev_public_id"), row.getString("brand_slug")), fragrance.fragranceSlug());
            assertThat(rows).as("Exactly one perfume for %s", fragrance.fragranceSlug()).hasSize(1);
            var perfume = rows.get(0);
            assertThat(perfume.brandSlug()).as("Brand for %s", fragrance.fragranceSlug()).isEqualTo("creed");
            if (fragrance.publicId() != null) {
                assertThat(perfume.publicId()).isEqualTo(fragrance.publicId());
            }
            stored.add(perfume);
            assertAssociationUniqueness(perfume.id());
        }
        assertThat(stored).extracting(StoredPerfume::slug).doesNotHaveDuplicates();
        assertThat(stored.stream().map(StoredPerfume::publicId).filter(Objects::nonNull).toList()).doesNotHaveDuplicates();
        // Check global uniqueness for the imported identifiers as well as uniqueness within this run.
        for (var perfume : stored) {
            if (perfume.publicId() != null) {
                assertThat(count("select count(*) from perfumes where scentrev_public_id = ?", perfume.publicId())).isEqualTo(1L);
            }
        }

        var afterCreed = creedRows();
        assertThat(afterCreed).as("Previously committed Creed rows, including Aventus, remain unchanged")
                .containsAll(priorCreed);
        for (var perfume : priorCreed) {
            var afterLinks = associationIds(perfume.id());
            for (var entry : priorLinks.get(perfume.id()).entrySet()) {
                assertThat(afterLinks.get(entry.getKey())).as("Existing %s links remain", entry.getKey())
                        .containsAll(entry.getValue());
            }
        }
        System.out.printf("Creed committed import: pages=%d, raw=%d, unique=%d, duplicates=%d, processed=%d%n",
                discovery.pageCount(), discovery.discoveredResultCount(), discovery.uniqueFragranceCount(),
                discovery.duplicateDiscoveryCount(), result.successfullyProcessedCount());
    }

    private List<StoredPerfume> creedRows() {
        return jdbc.query("""
                select p.id, p.fragrance_slug, p.scentrev_public_id, b.brand_slug
                from perfumes p join brands b on b.id = p.brand_id where b.brand_slug = ? order by p.id
                """, (row, index) -> new StoredPerfume(row.getLong("id"), row.getString("fragrance_slug"),
                        row.getString("scentrev_public_id"), row.getString("brand_slug")), "creed");
    }

    private Map<String, Set<Long>> associationIds(long perfumeId) {
        var ids = new HashMap<String, Set<Long>>();
        // Table names are test-owned constants, never provider input.
        for (String table : List.of("perfume_perfumers", "perfume_notes", "perfume_accords")) {
            ids.put(table, new HashSet<>(jdbc.queryForList("select id from " + table + " where perfume_id = ?", Long.class, perfumeId)));
        }
        return ids;
    }

    private void assertAssociationUniqueness(long perfumeId) {
        assertThat(count("""
                select count(*) from (
                    select perfume_id, perfumer_id from perfume_perfumers where perfume_id = ?
                    group by perfume_id, perfumer_id having count(*) > 1
                ) duplicates
                """, perfumeId)).isZero();
        assertThat(count("""
                select count(*) from (
                    select perfume_id, note_id, layer from perfume_notes where perfume_id = ?
                    group by perfume_id, note_id, layer having count(*) > 1
                ) duplicates
                """, perfumeId)).isZero();
        assertThat(count("""
                select count(*) from (
                    select perfume_id, accord_id from perfume_accords where perfume_id = ?
                    group by perfume_id, accord_id having count(*) > 1
                ) duplicates
                """, perfumeId)).isZero();
    }

    private Long count(String sql, Object... arguments) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments));
    }

    private record StoredPerfume(long id, String slug, String publicId, String brandSlug) { }
}
