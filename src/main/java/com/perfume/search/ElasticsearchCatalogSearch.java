package com.perfume.search;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class ElasticsearchCatalogSearch implements CatalogSearch {
    private static final Logger log = LoggerFactory.getLogger(ElasticsearchCatalogSearch.class);
    private final ElasticTransport transport;
    private final ObjectMapper mapper;
    private final String alias;
    private final Clock clock;
    private final Semaphore permits = new Semaphore(4);
    private final AtomicLong retryAt = new AtomicLong();

    ElasticsearchCatalogSearch(ElasticTransport transport, ObjectMapper mapper, String alias, Clock clock) {
        this.transport = transport; this.mapper = mapper; this.alias = alias; this.clock = clock;
    }

    @Override
    public Optional<Hits> search(int page, int size, String query, String brandSlug) {
        // Preserve the old API for deep pages without increasing Elasticsearch's memory window.
        if (query == null || query.isBlank() || (long) page * size + size > 10_000
                || clock.millis() < retryAt.get() || !permits.tryAcquire()) return Optional.empty();
        try {
            JsonNode response = transport.request("POST", "/" + alias + "/_search?allow_partial_search_results=false",
                    ElasticsearchQuery.body(mapper, page, size, query.strip(), brandSlug));
            if (!response.path("timed_out").isBoolean() || response.path("timed_out").asBoolean()
                    || response.path("_shards").path("failed").asInt(-1) != 0
                    || !response.path("hits").path("total").path("relation").asText().equals("eq")) throw invalid();
            var totalNode = response.path("hits").path("total").path("value");
            if (!totalNode.canConvertToLong() || totalNode.asLong() < 0) throw invalid();
            long total = totalNode.asLong();
            var hits = response.path("hits").path("hits");
            if (!hits.isArray() || hits.size() != Math.min(size, Math.max(0, total - (long) page * size))) throw invalid();
            var ids = new ArrayList<Long>();
            for (var hit : hits) {
                String id = hit.path("_id").asText();
                if (!id.matches("[1-9][0-9]*")) throw invalid();
                ids.add(Long.parseLong(id));
            }
            if (new HashSet<>(ids).size() != ids.size()) throw invalid();
            return Optional.of(new Hits(ids, total));
        } catch (RuntimeException unavailable) {
            long now = clock.millis();
            long previous = retryAt.getAndSet(now + 15_000);
            if (previous <= now) log.warn("Catalog Elasticsearch unavailable; using PostgreSQL for 15 seconds");
            return Optional.empty();
        } finally { permits.release(); }
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Incomplete search response"); }
}
