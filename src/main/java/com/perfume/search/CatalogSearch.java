package com.perfume.search;

import java.util.List;
import java.util.Optional;

/** Optional secondary index. An empty Optional requests the existing SQL search. */
@FunctionalInterface
public interface CatalogSearch {
    Optional<Hits> search(int page, int size, String query, String brandSlug);

    record Hits(List<Long> ids, long total) {
        public Hits { ids = List.copyOf(ids); }
    }

    static CatalogSearch disabled() { return (page, size, query, brand) -> Optional.empty(); }
}
