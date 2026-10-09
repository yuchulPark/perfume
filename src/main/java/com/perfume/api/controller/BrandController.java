package com.perfume.api.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.perfume.api.dto.BrandResponse;
import com.perfume.api.service.BrandQueryService;

@RestController
@RequestMapping("/api/brands")
public class BrandController {
    private final BrandQueryService service;
    public BrandController(BrandQueryService service) { this.service = service; }
    @GetMapping
    public List<BrandResponse> list() { return service.list(); }
}
