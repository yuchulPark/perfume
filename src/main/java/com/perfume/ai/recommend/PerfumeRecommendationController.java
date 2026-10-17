package com.perfume.ai.recommend;

import com.perfume.ai.search.PerfumeSemanticSearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/ai/perfumes")
public class PerfumeRecommendationController {
    private final ObjectProvider<PerfumeRecommendationService> service;
    public PerfumeRecommendationController(ObjectProvider<PerfumeRecommendationService> service) { this.service = service; }
    @PostMapping("/recommend")
    public PerfumeRecommendationResponse recommend(@RequestBody PerfumeSemanticSearchRequest request) {
        return active().recommend(request);
    }
    @PostMapping("/recommend/jobs")
    public ResponseEntity<RecommendationJobResponse> start(@RequestBody PerfumeSemanticSearchRequest request,
            @RequestHeader(value = "X-Recommendation-Token", required = false) String token) {
        return ResponseEntity.accepted().cacheControl(org.springframework.http.CacheControl.noStore()).body(active().start(request, token));
    }
    @GetMapping("/recommend/jobs/{jobId}")
    public ResponseEntity<RecommendationJobResponse> status(@PathVariable String jobId, @RequestHeader(value = "X-Recommendation-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(active().status(jobId, token));
    }
    @GetMapping("/recommend/jobs")
    public ResponseEntity<RecommendationJobResponse> find(@RequestHeader(value = "X-Recommendation-Token", required = false) String token) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(active().find(token));
    }
    @PostMapping("/recommend/jobs/{jobId}/cancel")
    public ResponseEntity<RecommendationJobResponse> cancel(@PathVariable String jobId, @RequestHeader(value = "X-Recommendation-Token", required = false) String token) {
        return ResponseEntity.accepted().cacheControl(org.springframework.http.CacheControl.noStore()).body(active().cancel(jobId, token));
    }
    private PerfumeRecommendationService active() {
        var active = service.getIfAvailable();
        if (active == null) throw new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_DISABLED);
        return active;
    }
}
