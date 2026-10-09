package com.perfume.scentrev.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.perfume.domain.Perfume;
import com.perfume.repository.PerfumeRepository;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.service.PerfumeSearchCandidate;
import com.perfume.scentrev.service.ScentRevOnDemandCacheAccess;
import com.perfume.scentrev.service.ScentRevOnDemandPerfumeService;
import com.perfume.scentrev.service.ScentRevPhase1ImportService;

/** Real existing Phase 1 DB only; provider and mapper mocks prohibit any network or import on this cached path. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.ondemand-db-live-test", matches = "true")
@SpringBootTest(classes = ScentRevOnDemandDatabaseSmokeTests.CachedPathConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never", "spring.jpa.show-sql=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ScentRevOnDemandDatabaseSmokeTests {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Perfume.class)
    @EnableJpaRepositories(basePackageClasses = PerfumeRepository.class)
    @Import({ScentRevOnDemandPerfumeService.class, ScentRevOnDemandCacheAccess.class})
    static class CachedPathConfiguration { } // No bulk services/entities/repositories are required by this test context.

    @Autowired private ScentRevOnDemandPerfumeService service;
    @Autowired private PerfumeRepository perfumes;
    @MockitoBean private ScentRevMcpClient client;
    @MockitoBean private ScentRevPhase1ImportService mapper;

    @Test
    void existingAventusSelectionAndSearchUsePostgreSqlWithZeroProviderRequestsOrWrites() {
        var prior = perfumes.findByFragranceSlug("creed-aventus").orElseThrow(() ->
                new AssertionError("The previously imported Creed/Aventus row is required; this test will not create it."));
        long countBefore = perfumes.count();
        var cached = service.getOrImportBySlug("creed-aventus");
        assertThat(cached.getId()).isEqualTo(prior.getId());
        assertThat(cached.getScentrevPublicId()).isEqualTo(prior.getScentrevPublicId());
        assertThat(cached.getName()).isEqualTo(prior.getName());
        assertThat(cached.getBrand().getBrandSlug()).isEqualTo("creed");
        var search = service.search("Aventus", 5);
        assertThat(search.candidates()).isNotEmpty().hasSizeLessThanOrEqualTo(5);
        assertThat(search.candidates()).allSatisfy(candidate -> {
            assertThat(candidate.cached()).isTrue();
            assertThat(candidate.source()).isEqualTo(PerfumeSearchCandidate.Source.LOCAL);
        });
        verifyNoInteractions(client, mapper);
        assertThat(perfumes.count()).isEqualTo(countBefore);
        System.out.printf("ScentRev on-demand cached DB summary:%nAventus row ID: %d%nlocal candidates: %d%n"
                + "remote search requests: 0%nremote profile requests: 0%nimports: 0%n", cached.getId(), search.candidates().size());
    }
}
