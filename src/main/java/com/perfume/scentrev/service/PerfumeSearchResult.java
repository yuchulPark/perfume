package com.perfume.scentrev.service;

import java.util.List;

public record PerfumeSearchResult(List<PerfumeSearchCandidate> candidates) {
    public PerfumeSearchResult { candidates = List.copyOf(candidates); }
}
