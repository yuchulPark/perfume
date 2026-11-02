package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ElasticsearchSearchTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"Aventus", "Creed", "Rose", "woody", "장미", "크리드", "avntus", "Aventus Creed", "x\" OR *:*"})
    void nameBrandNotesKoreanAndTyposAreLiteralWeightedQueries(String query) throws Exception {
        var body = mapper.readTree(ElasticsearchQuery.body(mapper, 2, 5, query, "creed"));
        assertThat(body.path("from").asInt()).isEqualTo(10);
        assertThat(body.path("size").asInt()).isEqualTo(5);
        assertThat(body.path("track_total_hits").asBoolean()).isTrue();
        assertThat(body.at("/query/bool/filter/0/term/brandSlug").asText()).isEqualTo("creed");
        var primary = body.at("/query/bool/should/0/multi_match");
        assertThat(primary.path("query").asText()).isEqualTo(query);
        assertThat(primary.path("fields").toString()).contains("name^8", "brandName^5", "topNotes^2", "accords^2");
        var fuzzy = body.at("/query/bool/should/3/multi_match");
        assertThat(fuzzy.path("analyzer").asText()).isEqualTo("catalog_plain");
        assertThat(fuzzy.path("fuzziness").asText()).isEqualTo("AUTO");
        assertThat(fuzzy.path("max_expansions").asInt()).isEqualTo(25);
        assertThat(body.path("sort").toString()).isEqualTo("[{\"_score\":\"desc\"},{\"perfumeId\":\"asc\"}]");
    }

    @Test void exactCountsOrderAndEmptyResultsAreAccepted() throws Exception {
        var transport = mock(ElasticTransport.class);
        when(transport.request(anyString(), anyString(), anyString())).thenReturn(response(3, 9, 2), response(0));
        var search = search(transport, Clock.systemUTC());
        assertThat(search.search(0, 2, "rose", null).orElseThrow()).isEqualTo(new CatalogSearch.Hits(List.of(9L, 2L), 3));
        assertThat(search.search(0, 2, "missing", null).orElseThrow()).isEqualTo(new CatalogSearch.Hits(List.of(), 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "shard", "missing-shards", "lower-bound", "duplicate", "zero-id", "bad-id", "short-page", "missing-total", "missing-timeout"})
    void incompleteResultsTriggerFallbackAndCooldown(String kind) throws Exception {
        ObjectNode response = response(2, 1, 2);
        switch (kind) {
            case "timeout" -> response.put("timed_out", true);
            case "shard" -> ((ObjectNode) response.path("_shards")).put("failed", 1);
            case "missing-shards" -> response.remove("_shards");
            case "lower-bound" -> ((ObjectNode) response.at("/hits/total")).put("relation", "gte");
            case "duplicate" -> ((ObjectNode) response.at("/hits/hits/1")).put("_id", "1");
            case "zero-id" -> ((ObjectNode) response.at("/hits/hits/0")).put("_id", "0");
            case "bad-id" -> ((ObjectNode) response.at("/hits/hits/0")).put("_id", "not-an-id");
            case "short-page" -> ((ObjectNode) response.at("/hits/total")).put("value", 3);
            case "missing-total" -> ((ObjectNode) response.path("hits")).remove("total");
            case "missing-timeout" -> response.remove("timed_out");
        }
        var transport = mock(ElasticTransport.class);
        when(transport.request(anyString(), anyString(), anyString())).thenReturn(response);
        var search = search(transport, Clock.systemUTC());
        assertThat(search.search(0, 3, "rose", null)).isEmpty();
        assertThat(search.search(0, 3, "rose", null)).isEmpty();
        verify(transport, times(1)).request(anyString(), contains("allow_partial_search_results=false"), anyString());
    }

    @Test void connectionFailureRecoversAfterCooldownWithoutRestart() throws Exception {
        var transport = mock(ElasticTransport.class);
        var clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1000L);
        when(transport.request(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(response(1, 1));
        var search = search(transport, clock);
        assertThat(search.search(0, 20, "rose", null)).isEmpty();
        when(clock.millis()).thenReturn(15999L);
        assertThat(search.search(0, 20, "rose", null)).isEmpty();
        when(clock.millis()).thenReturn(16000L);
        assertThat(search.search(0, 20, "rose", null)).isPresent();
        verify(transport, times(2)).request(anyString(), anyString(), anyString());
    }

    @Test void blankAndDeepPagesDoNotCallElasticsearch() {
        var transport = mock(ElasticTransport.class); var search = search(transport, Clock.systemUTC());
        assertThat(search.search(0, 20, "  ", null)).isEmpty();
        assertThat(search.search(500, 20, "rose", null)).isEmpty();
        assertThat(search.search(Integer.MAX_VALUE, 100, "rose", null)).isEmpty();
        verifyNoInteractions(transport);
    }

    @Test void saturatedSearchFallsBackWithoutWaiting() throws Exception {
        var entered = new CountDownLatch(4); var release = new CountDownLatch(1); var calls = new AtomicInteger();
        var empty = response(0);
        ElasticTransport transport = (method, path, body) -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(); }
            return empty;
        };
        var search = search(transport, Clock.systemUTC()); var pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) pool.submit(() -> search.search(0, 20, "rose", null));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(search.search(0, 20, "rose", null)).isEmpty();
            assertThat(calls.get()).isEqualTo(4);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    private ElasticsearchCatalogSearch search(ElasticTransport transport, Clock clock) {
        return new ElasticsearchCatalogSearch(transport, mapper, "perfumes-search", clock);
    }
    private ObjectNode response(long total, long... ids) throws Exception {
        var result = (ObjectNode) mapper.readTree("{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}");
        ((ObjectNode) result.at("/hits/total")).put("value", total);
        var hits = (com.fasterxml.jackson.databind.node.ArrayNode) result.at("/hits/hits");
        for (long id : ids) hits.addObject().put("_id", Long.toString(id));
        return result;
    }
}
