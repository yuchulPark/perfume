package com.perfume.ai.search;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/perfumes")
public class PerfumeSemanticSearchController {
    private final ObjectProvider<PerfumeSemanticSearchService> service;
    public PerfumeSemanticSearchController(ObjectProvider<PerfumeSemanticSearchService> service) { this.service = service; }
    @PostMapping("/search")
    public PerfumeSemanticSearchResponse search(@RequestBody PerfumeSemanticSearchRequest request) {
        var enabled = service.getIfAvailable();
        if (enabled == null) { throw new SemanticSearchFailure(SemanticSearchFailure.Code.SEARCH_DISABLED); }
        return enabled.search(request);
    }
}
