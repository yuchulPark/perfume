package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;

/** NOT part of local verification. Explicit opt-in against a separately approved disposable ES node only. */
@EnabledIfEnvironmentVariable(named = "PERFUME_ELASTICSEARCH_LIVE_TEST", matches = "true")
class ElasticsearchLiveTests {
    @Test void realNoriSynonymsTyposFiltersAndPaging() {
        String alias = "perfumes-test-" + UUID.randomUUID().toString().substring(0, 8);
        var env = new MockEnvironment().withProperty("perfume.elasticsearch.alias", alias)
                .withProperty("perfume.elasticsearch.url", required("PERFUME_ELASTICSEARCH_TEST_URL"))
                .withProperty("perfume.elasticsearch.ca-file", required("PERFUME_ELASTICSEARCH_TEST_CA_FILE"))
                .withProperty("perfume.elasticsearch.index-api-key-file", required("PERFUME_ELASTICSEARCH_TEST_API_KEY_FILE"));
        var options = ElasticsearchOptions.from(env, true); var mapper = new ObjectMapper();
        var transport = new ElasticHttpTransport(options, mapper, Duration.ofSeconds(30));
        var documents = List.of(
                new PerfumeSearchDocument(1, "Aventus", "Creed", "creed", null, 2010, List.of("Bergamot"), List.of(), List.of("Musk"), List.of(), List.of("woody")),
                new PerfumeSearchDocument(2, "Rose Garden", "Dior", "dior", null, null, List.of("Rose"), List.of("Jasmine"), List.of(), List.of(), List.of()),
                new PerfumeSearchDocument(3, "장미 정원", "향의기록", "archive", null, null, List.of("장미"), List.of(), List.of(), List.of(), List.of()),
                new PerfumeSearchDocument(4, "A Rose", "Creed", "creed", null, null, List.of("Rose"), List.of(), List.of(), List.of(), List.of()));
        CatalogSnapshot snapshot = new CatalogSnapshot() {
            public long count() { return documents.size(); }
            public List<PerfumeSearchDocument> next(long after, int size) { return documents.stream().filter(d -> d.perfumeId() > after).limit(size).toList(); }
            public void close() { }
        };
        var result = new ElasticsearchReindexer(transport, mapper, alias, Clock.systemUTC()).execute(snapshot);
        System.out.println("Live test retained disposable index for review: " + result.index());
        var search = new ElasticsearchCatalogSearch(transport, mapper, alias, Clock.systemUTC());
        for (String query : List.of("Aventus", "aventuss", "아벤투스", "BERGAMOT", "woody"))
            assertThat(search.search(0, 20, query, null).orElseThrow().ids()).contains(1L);
        assertThat(search.search(0, 20, "Jasmine", null).orElseThrow().ids()).containsExactly(2L);
        assertThat(search.search(0, 20, "장미", null).orElseThrow().ids()).contains(2L, 3L, 4L);
        assertThat(search.search(0, 20, "장미", "creed").orElseThrow().ids()).containsExactly(4L);
        var first = search.search(0, 1, "크리드", null).orElseThrow();
        var second = search.search(1, 1, "크리드", null).orElseThrow();
        assertThat(first.total()).isEqualTo(2); assertThat(second.total()).isEqualTo(2);
        assertThat(first.ids()).doesNotContainAnyElementsOf(second.ids());
    }
    private String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing disposable test setting: " + key);
        return value;
    }
}
