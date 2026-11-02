package com.perfume.search;

import java.util.List;

interface CatalogSnapshot extends AutoCloseable {
    long count();
    List<PerfumeSearchDocument> next(long afterId, int size);
    @Override void close();
}
