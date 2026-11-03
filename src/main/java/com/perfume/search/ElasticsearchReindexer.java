package com.perfume.search;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.perfume.search.SearchDiagnostics.*;

/** Build, verify, then atomically publish. No deletes and no writes through the serving alias. */
final class ElasticsearchReindexer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElasticsearchReindexer.class);
    private final ElasticTransport transport;
    private final ObjectMapper mapper;
    private final String alias;
    private final Clock clock;

    ElasticsearchReindexer(ElasticTransport transport, ObjectMapper mapper, String alias, Clock clock) {
        this.transport = transport; this.mapper = mapper; this.alias = alias; this.clock = clock;
    }

    record Result(String index, long sourceCount, long indexedCount, String previousIndex) { }

    Result execute(CatalogSnapshot snapshot) {
        Stage stage = Stage.SOURCE_COUNT;
        try {
            begin(log, stage);
            long started = clock.millis();
            long expected = snapshot.count();
            // Empty-source publication requires operator investigation, not automatic erasure of valid search.
            if (expected <= 0) throw failure(stage, Code.EMPTY_SOURCE, "Empty catalog; alias unchanged");
            stage = Stage.ALIAS_LOOKUP;
            begin(log, stage);
            JsonNode oldAlias = transport.request("GET", "/_alias/" + alias, null);
            String previous = previousIndex(oldAlias);
            String index = alias + "-v-" + clock.millis() + "-" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Catalog reindex preparing: sourceCount={}, index={}, previous={}", expected, index, previous);
            stage = Stage.INDEX_CREATE;
            begin(log, stage);
            acknowledged(transport.request("PUT", "/" + index, mapping()), stage);
            long processed = 0;
            long after = 0;
            while (true) {
                stage = Stage.SOURCE_READ;
                if (clock.millis() - started > Duration.ofMinutes(30).toMillis() || Thread.currentThread().isInterrupted())
                    throw failure(stage, Code.TIME_LIMIT, "Reindex time limit or interruption; alias unchanged");
                begin(log, stage);
                var batch = snapshot.next(after, 200);
                if (batch.isEmpty()) break;
                stage = Stage.BULK_INDEX;
                begin(log, stage);
                StringBuilder ndjson = new StringBuilder();
                for (var document : batch) {
                    if (document.perfumeId() <= after) throw failure(stage, Code.INVALID_SOURCE_ORDER, "Invalid snapshot ordering");
                    ndjson.append(json(Map.of("create", Map.of("_id", Long.toString(document.perfumeId()))))).append('\n');
                    ndjson.append(json(document)).append('\n');
                    after = document.perfumeId();
                }
                var response = transport.request("POST", "/" + index + "/_bulk", ndjson.toString());
                if (!response.path("errors").isBoolean() || !response.path("items").isArray()
                        || response.path("items").size() != batch.size())
                    throw failure(stage, Code.BULK_FAILED, "Bulk failure; alias unchanged");
                int offset = 0;
                for (var item : response.path("items")) {
                    var created = item.path("create");
                    if (created.path("status").asInt() != 201 || created.has("error")
                            || !created.path("_id").asText().equals(Long.toString(batch.get(offset++).perfumeId()))) {
                        var safe = details(stage, Code.BULK_ITEM_FAILED, "Bulk item failure; alias unchanged", 0, null,
                                created.path("error").path("type").asText());
                        log.error("Catalog bulk diagnostic: stage={} item_status={} es_type={}", stage,
                                created.path("status").asInt(), safe.esType);
                        throw safe;
                    }
                }
                if (response.path("errors").asBoolean()) throw failure(stage, Code.BULK_FAILED, "Bulk failure; alias unchanged");
                processed += batch.size();
                log.info("Catalog reindex progress: processed={}, sourceCount={}, index={}", processed, expected, index);
                if (processed > expected) throw failure(stage, Code.COUNT_MISMATCH, "Snapshot count mismatch; alias unchanged");
            }
            stage = Stage.INDEX_REFRESH;
            begin(log, stage);
            var refresh = transport.request("POST", "/" + index + "/_refresh", null);
            if (refresh.path("_shards").path("failed").asInt(-1) != 0)
                throw failure(stage, Code.REFRESH_FAILED, "Refresh failed; alias unchanged");
            stage = Stage.COUNT_VERIFY;
            begin(log, stage);
            var count = transport.request("GET", "/" + index + "/_count", null);
            long indexed = count.path("count").asLong(-1);
            log.info("Catalog reindex stage={} sourceCount={} processed={} indexedCount={}", stage, expected, processed, indexed);
            if (count.path("_shards").path("failed").asInt(-1) != 0 || indexed != expected || processed != expected)
                throw failure(stage, Code.COUNT_MISMATCH, "Source/index count mismatch; alias unchanged");
            stage = Stage.ALIAS_RECHECK;
            begin(log, stage);
            // Catch out-of-band alias changes before publishing. DB lock serializes all supported CLI invocations.
            if (!oldAlias.equals(transport.request("GET", "/_alias/" + alias, null)))
                throw failure(stage, Code.ALIAS_CHANGED, "Alias changed during reindex; publication aborted");
            var actions = new ArrayList<Map<String, Object>>();
            if (previous != null) actions.add(Map.of("remove", Map.of("index", previous, "alias", alias, "must_exist", true)));
            actions.add(Map.of("add", Map.of("index", index, "alias", alias, "is_write_index", false)));
            log.info("Catalog reindex verified: sourceCount={}, indexedCount={}, index={}; publishing alias={}", expected, indexed, index, alias);
            stage = Stage.ALIAS_SWAP;
            begin(log, stage);
            // A timeout here is an uncertain outcome: inspect the alias, never delete or blindly retry.
            acknowledged(transport.request("POST", "/_aliases", json(Map.of("actions", actions))), stage);
            stage = Stage.ALIAS_VERIFY;
            begin(log, stage);
            var current = transport.request("GET", "/_alias/" + alias, null);
            if (!index.equals(previousIndex(current))) throw failure(stage, Code.INVALID_ALIAS, "Verify alias publication manually");
            return new Result(index, expected, indexed, previous);
        } catch (RuntimeException failure) {
            throw report(log, stage, failure);
        }
    }

    private String previousIndex(JsonNode aliases) {
        if (!aliases.isObject() || aliases.size() > 1)
            throw failure(Stage.ALIAS_LOOKUP, Code.INVALID_ALIAS, "Expected a single catalog alias target");
        if (aliases.isEmpty()) return null;
        String name = aliases.fieldNames().next();
        if (!name.startsWith(alias + "-v-") || !name.matches("[a-z0-9-]+")
                || !aliases.path(name).path("aliases").has(alias))
            throw failure(Stage.ALIAS_LOOKUP, Code.INVALID_ALIAS, "Unexpected alias target");
        return name;
    }

    private String mapping() {
        try (var input = getClass().getResourceAsStream("/elasticsearch/perfumes-v1.json")) {
            if (input == null) throw new IllegalStateException();
            return mapper.readTree(input).toString();
        } catch (Exception invalid) { throw failure(Stage.INDEX_CREATE, Code.MAPPING_FAILED, "Cannot load catalog index mapping"); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw failure(Stage.BULK_INDEX, Code.ENCODING_FAILED, "Cannot encode catalog index request"); }
    }
    private static void acknowledged(JsonNode response, Stage stage) {
        if (!response.path("acknowledged").asBoolean(false) || response.path("errors").asBoolean(false))
            throw failure(stage, Code.NOT_ACKNOWLEDGED, "Elasticsearch did not acknowledge index/alias operation; inspect alias state");
    }
}
