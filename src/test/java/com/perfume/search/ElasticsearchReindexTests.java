package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ElasticsearchReindexTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ALIAS = "perfumes-search";
    private static final String OLD = "perfumes-search-v-old";

    @Test void publishOnlyAfterEveryItemRefreshAndCountPassAndNeverDeleteOldIndex() throws Exception {
        var fixture = new Fixture();
        var result = fixture.reindex();
        assertThat(result.sourceCount()).isEqualTo(2); assertThat(result.indexedCount()).isEqualTo(2);
        assertThat(result.previousIndex()).isEqualTo(OLD);
        assertThat(result.index()).startsWith(ALIAS + "-v-").isNotEqualTo(OLD);
        assertThat(fixture.calls).containsSubsequence("PUT /" + result.index(), "POST /" + result.index() + "/_bulk",
                "POST /" + result.index() + "/_refresh", "GET /" + result.index() + "/_count", "POST /_aliases");
        assertThat(fixture.calls).noneMatch(call -> call.startsWith("DELETE"));
        assertThat(fixture.aliasBody.at("/actions/0/remove/index").asText()).isEqualTo(OLD);
        assertThat(fixture.aliasBody.at("/actions/0/remove/must_exist").asBoolean()).isTrue();
        assertThat(fixture.aliasBody.at("/actions/1/add/index").asText()).isEqualTo(result.index());
        assertThat(fixture.bulk.split("\n")).hasSize(4);
        assertThat(mapper.readTree(fixture.bulk.split("\n")[1]).path("topNotes").get(0).asText()).isEqualTo("Rose");
        verify(fixture.snapshot).next(0, 200); verify(fixture.snapshot).next(7, 200);
    }
    @Test void firstIndexAddsAnAliasWithoutRemovingAnything() {
        var fixture = new Fixture(); fixture.alias = mapper.createObjectNode();
        assertThat(fixture.reindex().previousIndex()).isNull();
        assertThat(fixture.aliasBody.path("actions")).hasSize(1);
        assertThat(fixture.aliasBody.at("/actions/0/add/alias").asText()).isEqualTo(ALIAS);
    }
    @ParameterizedTest
    @ValueSource(strings = {"bulk-errors", "bulk-item", "wrong-id", "missing-items", "count", "count-shard", "refresh", "http", "alias-race", "create"})
    void failuresNeverPublishAnIncompleteIndex(String failure) {
        var fixture = new Fixture(); fixture.failure = failure;
        assertThatThrownBy(fixture::reindex).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.calls).doesNotContain("POST /_aliases");
        assertThat(fixture.calls).noneMatch(call -> call.startsWith("DELETE"));
    }
    @Test void emptySourceProtectsTheServingAliasBeforeAnyHttp() {
        var fixture = new Fixture(); when(fixture.snapshot.count()).thenReturn(0L);
        assertThatThrownBy(fixture::reindex).hasMessageContaining("Empty catalog");
        assertThat(fixture.calls).isEmpty();
    }
    @Test void unorderedOrIncompleteSnapshotDoesNotPublish() {
        var fixture = new Fixture(); when(fixture.snapshot.next(0, 200)).thenReturn(List.of(document(7), document(2)));
        assertThatThrownBy(fixture::reindex).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.calls).doesNotContain("POST /_aliases");
        fixture = new Fixture(); when(fixture.snapshot.count()).thenReturn(3L);
        assertThatThrownBy(fixture::reindex).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.calls).doesNotContain("POST /_aliases");
    }
    @Test void aliasesWithForeignOrMultipleTargetsAreRejected() {
        var fixture = new Fixture(); fixture.alias = mapper.valueToTree(Map.of("foreign", Map.of("aliases", Map.of(ALIAS, Map.of()))));
        assertThatThrownBy(fixture::reindex).hasMessageContaining("Unexpected alias");
        assertThat(fixture.calls).hasSize(1);
    }
    @Test void ambiguousAliasFailureLeavesBothIndicesForOperatorInspection() {
        var fixture = new Fixture(); fixture.failure = "publish-timeout";
        assertThatThrownBy(fixture::reindex).isInstanceOf(IllegalStateException.class);
        assertThat(fixture.calls).contains("POST /_aliases").noneMatch(call -> call.startsWith("DELETE"));
    }
    @Test void mappingSeparatesKoreanSynonymsAndFuzzinessAndHasEveryDocumentField() throws Exception {
        try (var stream = getClass().getResourceAsStream("/elasticsearch/perfumes-v1.json")) {
            var mapping = mapper.readTree(stream);
            assertThat(mapping.at("/settings/analysis/analyzer/catalog_search/filter").toString()).contains("catalog_synonyms");
            assertThat(mapping.at("/settings/analysis/analyzer/catalog_plain/filter").toString()).doesNotContain("synonym");
            assertThat(mapping.at("/settings/analysis/tokenizer/catalog_nori/decompound_mode").asText()).isEqualTo("discard");
            assertThat(mapping.at("/mappings/properties").size()).isEqualTo(11);
            assertThat(mapping.at("/mappings/properties/brandSlug/type").asText()).isEqualTo("keyword");
            assertThat(mapping.at("/mappings/dynamic").asText()).isEqualTo("strict");
        }
    }
    static PerfumeSearchDocument document(long id) {
        return new PerfumeSearchDocument(id, "Aventus", "Creed", "creed", null, 2010,
                List.of("Rose"), List.of(), List.of("Musk"), List.of(), List.of("woody"));
    }
    private class Fixture {
        final CatalogSnapshot snapshot = mock(CatalogSnapshot.class);
        final List<String> calls = new ArrayList<>();
        String failure = ""; String bulk; JsonNode aliasBody; int aliasReads;
        JsonNode alias = mapper.valueToTree(Map.of(OLD, Map.of("aliases", Map.of(ALIAS, Map.of("is_write_index", false)))));
        Fixture() {
            when(snapshot.count()).thenReturn(2L);
            when(snapshot.next(0, 200)).thenReturn(List.of(document(2), document(7)));
            when(snapshot.next(7, 200)).thenReturn(List.of());
        }
        ElasticsearchReindexer.Result reindex() {
            return new ElasticsearchReindexer(this::request, mapper, ALIAS, Clock.systemUTC()).execute(snapshot);
        }
        JsonNode request(String method, String path, String body) {
            calls.add(method + " " + path);
            try {
                if (path.equals("/_alias/" + ALIAS)) {
                    if (++aliasReads == 2 && failure.equals("alias-race")) return mapper.createObjectNode();
                    return alias;
                }
                if (method.equals("PUT")) return mapper.valueToTree(Map.of("acknowledged", !failure.equals("create")));
                if (path.endsWith("/_bulk")) {
                    bulk = body;
                    if (failure.equals("http")) throw new IllegalStateException();
                    var items = new ArrayList<Map<String, Object>>();
                    for (long id : List.of(2L, 7L)) items.add(Map.of("create", Map.of("_id", failure.equals("wrong-id") ? "4" : Long.toString(id),
                            "status", failure.equals("bulk-item") ? 429 : 201)));
                    return mapper.valueToTree(Map.of("errors", failure.equals("bulk-errors"), "items", failure.equals("missing-items") ? List.of() : items));
                }
                if (path.endsWith("/_refresh")) return mapper.valueToTree(Map.of("_shards", Map.of("failed", failure.equals("refresh") ? 1 : 0)));
                if (path.endsWith("/_count")) return mapper.valueToTree(Map.of("count", failure.equals("count") ? 1 : 2,
                        "_shards", Map.of("failed", failure.equals("count-shard") ? 1 : 0)));
                if (path.equals("/_aliases")) {
                    aliasBody = mapper.readTree(body);
                    String index = aliasBody.path("actions").get(aliasBody.path("actions").size() - 1).path("add").path("index").asText();
                    alias = mapper.valueToTree(Map.of(index, Map.of("aliases", Map.of(ALIAS, Map.of("is_write_index", false)))));
                    if (failure.equals("publish-timeout")) throw new IllegalStateException();
                    return mapper.valueToTree(Map.of("acknowledged", true));
                }
                throw new AssertionError(method + " " + path);
            } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
    }
}
