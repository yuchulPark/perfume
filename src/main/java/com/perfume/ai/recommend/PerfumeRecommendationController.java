package com.perfume.ai.recommend;

import com.perfume.ai.search.PerfumeSemanticSearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/perfumes")
public class PerfumeRecommendationController {
    private final ObjectProvider<PerfumeRecommendationService> service;
    public PerfumeRecommendationController(ObjectProvider<PerfumeRecommendationService> service) { this.service = service; }
    @PostMapping("/recommend")
    public PerfumeRecommendationResponse recommend(@RequestBody PerfumeSemanticSearchRequest request) {
        var active = service.getIfAvailable();
        if (active == null) { throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_DISABLED); }
        return active.recommend(request);
    }
}
