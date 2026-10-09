package com.perfume.scentrev.curated;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

/** Classpath configuration only. Loading has no provider or database side effects. */
@Component
public class ScentRevCuratedBrandConfiguration {
    private final ObjectMapper mapper;
    public ScentRevCuratedBrandConfiguration(ObjectMapper mapper) { this.mapper = mapper; }

    public CuratedBrandCatalog load() {
        try (var raw = new ClassPathResource("scentrev/curated-brand-source.json").getInputStream();
             var mapping = new ClassPathResource("scentrev/curated-brands.json").getInputStream()) {
            return new CuratedBrandCatalog(mapper.readValue(raw, new TypeReference<List<String>>() { }),
                    mapper.readValue(mapping, new TypeReference<List<CuratedBrandDefinition>>() { }));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load curated ScentRev source/mapping resources; review their JSON.");
        }
    }
}
