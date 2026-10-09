package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;

import com.perfume.domain.Brand;
import com.perfume.repository.BrandRepository;
import com.perfume.scentrev.curated.CuratedBrandDefinition;
import com.perfume.scentrev.curated.ScentRevCuratedBrandConfiguration;
import com.perfume.scentrev.curated.ScentRevCuratedBrandDbImportService;

/** Explicit PostgreSQL Brand-only seed; no provider client or fragrance-import bean is loaded. */
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.curated-brand-db-import", matches = "true")
@SpringBootTest(classes = ScentRevCuratedBrandDbImportSmokeTests.SeedConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
                "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevCuratedBrandDbImportSmokeTests {
    @Autowired private ScentRevCuratedBrandDbImportService importer;
    @Autowired private ScentRevCuratedBrandConfiguration configuration;
    @Autowired private BrandRepository brands;

    @Test
    void seedsVerifiedBrandIdentitiesExactlyOnce() {
        var result = importer.importVerifiedBrands();
        System.out.printf("==================================================%n"
                        + "ScentRev curated Brand DB import result%n"
                        + "==================================================%n"
                        + "selected  = %d%ninserted  = %d%nupdated   = %d%nunchanged = %d%nprocessed = %d%n"
                        + "==================================================%n",
                result.selectedDefinitions(), result.inserted(), result.updated(), result.unchanged(), result.processed());
        assertThat(result.selectedDefinitions()).isEqualTo(161);
        assertThat(result.processed()).isEqualTo(161);
        var selected = configuration.load().parents().stream().filter(CuratedBrandDefinition::importable).toList();
        var stored = brands.findAll();
        assertThat(stored).extracting(Brand::getBrandSlug).doesNotHaveDuplicates()
                .containsAll(selected.stream().map(CuratedBrandDefinition::brandSlug).toList());
        for (var definition : selected) {
            assertThat(stored.stream().filter(brand -> definition.brandSlug().equals(brand.getBrandSlug())).findFirst().orElseThrow().getName())
                    .isEqualTo(definition.canonicalDisplayName());
        }
        System.out.println("Brand DB seed completed successfully.");
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Brand.class)
    @EnableJpaRepositories(basePackageClasses = BrandRepository.class)
    @Import({ScentRevCuratedBrandConfiguration.class, ScentRevCuratedBrandDbImportService.class})
    static class SeedConfiguration { }
}
