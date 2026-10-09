package com.perfume.api.service;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.perfume.api.dto.BrandResponse;
import com.perfume.repository.BrandRepository;

@Service
@Transactional(readOnly = true)
public class BrandQueryService {
    private final BrandRepository brands;
    public BrandQueryService(BrandRepository brands) { this.brands = brands; }
    public List<BrandResponse> list() {
        return brands.findAllByOrderByNameAscIdAsc().stream()
                .map(brand -> new BrandResponse(brand.getId(), brand.getName(), brand.getBrandSlug())).toList();
    }
}
