package com.perfume.scentrev.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;
import com.perfume.domain.Brand;
import com.perfume.domain.Perfume;
import com.perfume.repository.*;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;
import com.perfume.scentrev.dto.ScentRevIdentity;

/** Test-owned memory behind repository mocks; the actual Phase 1 mapper is spied, not replaced. */
class OnDemandTestStore {
    final PerfumeRepository perfumes = mock(PerfumeRepository.class);
    final BrandRepository brands = mock(BrandRepository.class);
    final Map<String, Perfume> perfumeRows = new LinkedHashMap<>();
    final Map<String, Brand> brandRows = new LinkedHashMap<>();
    final ScentRevPhase1ImportService mapper;
    final ScentRevOnDemandCacheAccess cache;
    Runnable databaseCheck = () -> { };
    private final AtomicLong ids = new AtomicLong();

    OnDemandTestStore() {
        when(perfumes.findByFragranceSlug(anyString())).thenAnswer(call -> {
            databaseCheck.run();
            return Optional.ofNullable(perfumeRows.get(call.getArgument(0)));
        });
        when(perfumes.findByScentrevPublicId(anyString())).thenAnswer(call -> {
            databaseCheck.run();
            String id = call.getArgument(0);
            return perfumeRows.values().stream().filter(perfume -> id.equals(perfume.getScentrevPublicId())).findFirst();
        });
        when(perfumes.save(any(Perfume.class))).thenAnswer(call -> {
            databaseCheck.run();
            Perfume perfume = call.getArgument(0);
            ReflectionTestUtils.setField(perfume, "id", ids.incrementAndGet());
            perfumeRows.put(perfume.getFragranceSlug(), perfume);
            return perfume;
        });
        when(brands.findByBrandSlug(anyString())).thenAnswer(call -> {
            databaseCheck.run();
            return Optional.ofNullable(brandRows.get(call.getArgument(0)));
        });
        when(brands.save(any(Brand.class))).thenAnswer(call -> {
            databaseCheck.run();
            Brand brand = call.getArgument(0);
            ReflectionTestUtils.setField(brand, "id", ids.incrementAndGet());
            brandRows.put(brand.getBrandSlug(), brand);
            return brand;
        });
        mapper = spy(new ScentRevPhase1ImportService(brands, perfumes, mock(PerfumerRepository.class),
                mock(PerfumePerfumerRepository.class), mock(NoteRepository.class), mock(PerfumeNoteRepository.class),
                mock(AccordRepository.class), mock(PerfumeAccordRepository.class)));
        cache = new ScentRevOnDemandCacheAccess(perfumes, mapper);
    }

    Perfume remember(String slug, String publicId) {
        var perfume = new Perfume(publicId, slug, "Aventus", null, null, null, new Brand("Creed", "creed"));
        ReflectionTestUtils.setField(perfume, "id", ids.incrementAndGet());
        perfumeRows.put(slug, perfume);
        return perfume;
    }

    static ScentRevFragranceProfileResponse profile(String slug, String publicId) {
        return new ScentRevFragranceProfileResponse(new ScentRevIdentity("Selected", publicId, "Creed", "creed",
                slug, null, null, null, null, null), null, List.of(), null, null, null, null, null);
    }
}
