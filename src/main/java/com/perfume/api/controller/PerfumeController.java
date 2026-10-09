package com.perfume.api.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import com.perfume.api.dto.*;
import com.perfume.api.service.PerfumeQueryService;

@RestController
@RequestMapping("/api/perfumes")
public class PerfumeController {
    private final PerfumeQueryService service;
    public PerfumeController(PerfumeQueryService service) { this.service = service; }
    @GetMapping({"", "/search"})
    public PageResponse<PerfumeSummaryResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 255) String q,
            @RequestParam(required = false) @Size(max = 255) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String brandSlug) {
        return service.list(page, size, q, brandSlug);
    }
    @GetMapping("/{id}")
    public PerfumeDetailResponse detail(@PathVariable @Positive Long id) { return service.detail(id); }
}
