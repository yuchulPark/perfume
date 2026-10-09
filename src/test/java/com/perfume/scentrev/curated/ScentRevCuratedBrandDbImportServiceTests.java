package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.domain.Brand;
import com.perfume.domain.Perfume;
import com.perfume.repository.BrandRepository;
import com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType;
import com.perfume.scentrev.curated.CuratedBrandDefinition.SourceEntry;
import com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus;

/** Local configuration and a repository fake only: no Spring datasource or provider client. */
class ScentRevCuratedBrandDbImportServiceTests {
    private final ScentRevCuratedBrandConfiguration configuration = mock(ScentRevCuratedBrandConfiguration.class);
    private final BrandRepository brands = mock(BrandRepository.class);
    private final Map<String, Brand> rows = new LinkedHashMap<>();
    private final ScentRevCuratedBrandDbImportService importer = new ScentRevCuratedBrandDbImportService(configuration, brands);
    private long nextId = 1000;

    @BeforeEach
    void repositoryUsesTheUniqueProviderSlug() {
        when(brands.findByBrandSlug(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        when(brands.save(any(Brand.class))).thenAnswer(call -> {
            Brand brand = call.getArgument(0);
            if (rows.containsKey(brand.getBrandSlug())) { throw new DataIntegrityViolationException("Duplicate fixture slug"); }
            ReflectionTestUtils.setField(brand, "id", nextId++);
            rows.put(brand.getBrandSlug(), brand);
            return brand;
        });
    }

    @Test
    void selectsOnlyVerifiedAndEnabledDefinitions() {
        when(configuration.load()).thenReturn(catalog(
                parent("approved", "Preferred", "Provider", "approved", true, VERIFIED, 1),
                parent("verified-off", "Off", "Off", "verified-off", false, VERIFIED, 2),
                parent("disabled", "Disabled", "Disabled", null, false, DISABLED, 3),
                parent("disabled-on", "Disabled On", "Disabled On", "disabled-on", true, DISABLED, 4),
                parent("unresolved", "Unresolved", "Unresolved", null, true, UNRESOLVED, 5),
                parent("unresolved-off", "Collaboration", "Zara", null, false, UNRESOLVED, 6)));
        var result = importer.importVerifiedBrands();
        assertThat(result).isEqualTo(new ScentRevCuratedBrandDbImportResult(1, 1, 0, 0));
        assertThat(result.processed()).isEqualTo(1);
        assertThat(rows.keySet()).containsExactly("approved");
        assertThat(rows.get("approved").getName()).isEqualTo("Preferred");
        verify(brands).findByBrandSlug("approved");
        verify(brands).save(any(Brand.class));
        verifyNoMoreInteractions(brands);
    }

    @Test
    void currentConfigurationSeeds161BrandsAndPreservesApprovedPreferredProviderDistinctions() {
        var catalog = useCurrentConfiguration();
        var eligible = catalog.parents().stream().filter(CuratedBrandDefinition::importable).toList();
        assertThat(eligible).hasSize(161).extracting(CuratedBrandDefinition::canonicalBrandKey)
                .doesNotContain("curated-090", "curated-096", "curated-133", "curated-145");
        assertThat(catalog.parents()).hasSize(165);
        assertThat(catalog.rawLabels()).hasSize(187);
        var result = importer.importVerifiedBrands();
        assertThat(result).isEqualTo(new ScentRevCuratedBrandDbImportResult(161, 161, 0, 0));
        assertThat(result.processed()).isEqualTo(161);
        assertThat(rows).hasSize(161);
        var protectedMappings = List.of(
                new Mapping("curated-035", "Hermès", "Hermès", "herm-s"),
                new Mapping("curated-067", "Van Cleef & Arpels", "Van Cleef & Arpels", "van-cleef-arpels"),
                new Mapping("curated-093", "Paco Rabanne", "Rabanne", "rabanne"),
                new Mapping("curated-102", "Thierry Mugler", "Mugler", "mugler"),
                new Mapping("curated-111", "Ed Hardy", "Christian Audigier", "christian-audigier"),
                new Mapping("curated-161", "D.S. & Durga", "DS&Durga", "ds-durga"),
                new Mapping("curated-110", "Dsquared2", "Dsquared", "dsquared"),
                new Mapping("curated-165", "Kierin NYC", "Kierin", "kierin"),
                new Mapping("curated-124", "프레드릭 엠", "Frederic M", "frederic-m"),
                new Mapping("curated-125", "알레산드로", "Alessandro", "alessandro"),
                new Mapping("curated-128", "리퀴드 이미지네흐", "Les Liquides Imaginaires", "les-liquides-imaginaires"));
        for (var expected : protectedMappings) {
            var definition = eligible.stream().filter(parent -> parent.canonicalBrandKey().equals(expected.key())).findFirst().orElseThrow();
            assertThat(definition.canonicalDisplayName()).isEqualTo(expected.display());
            assertThat(definition.resolutionQuery()).isEqualTo(expected.provider());
            assertThat(definition.brandSlug()).isEqualTo(expected.slug());
            assertThat(rows.get(expected.slug()).getName()).isEqualTo(expected.display());
        }
        assertThat(rows.values()).extracting(Brand::getName)
                .doesNotContain("퍼퓸 드 스퀘어", "스위스 컬렉션", "미카로카", "조말론 & 자라");
        assertThat(rows.values()).extracting(Brand::getBrandSlug).doesNotHaveDuplicates();
        assertThat(rows.keySet().stream().filter("olfactive-studio"::equals)).hasSize(1);
        verify(brands, times(161)).findByBrandSlug(anyString());
        verify(brands, times(161)).save(any(Brand.class));
        verifyNoMoreInteractions(brands);
    }

    @Test
    void duplicateEligibleSlugFailsBeforeAnyRepositoryAccessEvenAfterEarlierValidDefinitions() {
        when(configuration.load()).thenReturn(catalog(
                parent("first", "First", "First", "first", true, VERIFIED, 1),
                parent("second", "Second", "Second", "second", true, VERIFIED, 2),
                parent("duplicate", "Other Display", "First", "first", true, VERIFIED, 3)));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate verified provider slug");
        assertThat(rows).isEmpty();
        verifyNoInteractions(brands);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingVerifiedSlugFailsBeforePersistence(String slug) {
        when(configuration.load()).thenAnswer(call -> catalog(parent("invalid", "Preferred", "Provider", slug, true, VERIFIED, 1)));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(brands);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void missingPreferredNameFailsBeforePersistence(String name) {
        when(configuration.load()).thenAnswer(call -> catalog(parent("invalid", name, "Provider", "provider", true, VERIFIED, 1)));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(brands);
    }

    @Test
    void malformedConfigurationFailsBeforeRepositoryAccess() {
        when(configuration.load()).thenThrow(new IllegalStateException("Cannot load curated ScentRev source/mapping resources; review their JSON."));
        assertThatThrownBy(importer::importVerifiedBrands).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(brands);
    }

    @Test
    void secondIdenticalImportInsertsNothingAndPreservesEveryRowAndId() {
        useCurrentConfiguration();
        importer.importVerifiedBrands();
        var firstRows = new LinkedHashMap<>(rows);
        var firstIds = rows.values().stream().map(Brand::getId).toList();
        var second = importer.importVerifiedBrands();
        assertThat(second).isEqualTo(new ScentRevCuratedBrandDbImportResult(161, 0, 0, 161));
        assertThat(second.processed()).isEqualTo(161);
        assertThat(rows).hasSize(161);
        assertThat(rows.values()).extracting(Brand::getId).containsExactlyElementsOf(firstIds);
        rows.forEach((slug, brand) -> assertThat(brand).isSameAs(firstRows.get(slug)));
        verify(brands, times(322)).findByBrandSlug(anyString());
        verify(brands, times(161)).save(any(Brand.class));
        verifyNoMoreInteractions(brands);
    }

    @Test
    void updatesMatchingSlugInPlaceAndKeepsExistingPerfumeReference() {
        when(configuration.load()).thenReturn(catalog(parent("ed", "Ed Hardy", "Christian Audigier", "christian-audigier", true, VERIFIED, 1)));
        var existing = existingBrand("Christian Audigier", "christian-audigier", 41L);
        var perfume = new Perfume("existing-public-id", "existing-fragrance", "Existing Perfume", null, null, null, existing);
        var result = importer.importVerifiedBrands();
        assertThat(result).isEqualTo(new ScentRevCuratedBrandDbImportResult(1, 0, 1, 0));
        assertThat(rows.get("christian-audigier")).isSameAs(existing);
        assertThat(existing.getName()).isEqualTo("Ed Hardy");
        assertThat(existing.getId()).isEqualTo(41L);
        assertThat(existing.getBrandSlug()).isEqualTo("christian-audigier");
        assertThat(perfume.getBrand()).isSameAs(existing);
        verify(brands).findByBrandSlug("christian-audigier");
        verifyNoMoreInteractions(brands);
    }

    @Test
    void unrelatedRowsAreNotDeletedAndMatchingDisplayDoesNotOverrideSlugIdentity() {
        when(configuration.load()).thenReturn(catalog(parent("hermes", "Hermès", "Hermès", "herm-s", true, VERIFIED, 1)));
        var unrelated = existingBrand("Hermès", "unrelated-slug", 91L);
        var retained = existingBrand("미카로카", "previous-local-brand", 92L);
        var result = importer.importVerifiedBrands();
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(rows).hasSize(3);
        assertThat(rows.get("unrelated-slug")).isSameAs(unrelated);
        assertThat(rows.get("previous-local-brand")).isSameAs(retained);
        assertThat(rows.get("herm-s")).isNotSameAs(unrelated);
        assertThat(unrelated.getId()).isEqualTo(91L);
        assertThat(retained.getName()).isEqualTo("미카로카");
        verify(brands).findByBrandSlug("herm-s");
        verify(brands).save(any(Brand.class));
        verifyNoMoreInteractions(brands);
    }

    private CuratedBrandCatalog useCurrentConfiguration() {
        var catalog = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();
        when(configuration.load()).thenReturn(catalog);
        return catalog;
    }

    private Brand existingBrand(String name, String slug, Long id) {
        var brand = new Brand(name, slug);
        ReflectionTestUtils.setField(brand, "id", id);
        rows.put(slug, brand);
        return brand;
    }

    static CuratedBrandCatalog catalog(CuratedBrandDefinition... parents) {
        var definitions = List.of(parents);
        var raw = definitions.stream().flatMap(parent -> parent.sources().stream())
                .sorted(java.util.Comparator.comparingInt(SourceEntry::sourceIndex)).map(SourceEntry::rawLabel).toList();
        return new CuratedBrandCatalog(raw, definitions);
    }

    static CuratedBrandDefinition parent(String key, String display, String provider, String slug,
            boolean enabled, VerificationStatus status, int index) {
        return new CuratedBrandDefinition(key, display, provider, slug, enabled, status,
                slug == null ? null : "Offline verified fixture evidence", null, null,
                List.of(new SourceEntry(index, "source-" + index, EntryType.BRAND)));
    }

    private record Mapping(String key, String display, String provider, String slug) { }
}
