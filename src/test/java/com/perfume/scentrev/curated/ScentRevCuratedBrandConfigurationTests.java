package com.perfume.scentrev.curated;

import static org.assertj.core.api.Assertions.*;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType.BRAND;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType.COLLECTION;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType.ALIAS;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.EntryType.COLLABORATION;
import static com.perfume.scentrev.curated.CuratedBrandDefinition.VerificationStatus.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.curated.CuratedBrandDefinition.SourceEntry;

class ScentRevCuratedBrandConfigurationTests {
    private final CuratedBrandCatalog catalog = new ScentRevCuratedBrandConfiguration(new ObjectMapper()).load();

    @Test
    void rawSourcePreservesSuppliedOrderWithOnlyTwoApprovedSpellingCorrections() throws Exception {
        assertThat(catalog.rawLabels()).hasSize(187);
        assertThat(catalog.rawLabels().get(0)).isEqualTo("크리드");
        assertThat(catalog.rawLabels().get(123)).isEqualTo("프레드릭 엠");
        assertThat(catalog.rawLabels().get(124)).isEqualTo("알레산드로");
        assertThat(catalog.rawLabels().get(144)).isEqualTo("조말론 & 자라");
        assertThat(catalog.rawLabels().get(186)).isEqualTo("프라팡 퍼퓸");
        var originalLabels = new ArrayList<>(catalog.rawLabels());
        originalLabels.set(123, "프레데릭 엠");
        originalLabels.set(124, "알렉산드로");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", originalLabels).getBytes(StandardCharsets.UTF_8)));
        // Reverse only the approved corrections before checking the original user-pasted list checksum.
        assertThat(digest).isEqualTo("b82c0417a825d7c37ad851cd34f3c65ee6cd7a21613cea3fdde7aa75e5ec14ab");
        assertThat(catalog.entryCount(COLLECTION)).isEqualTo(23);
        assertThat(catalog.entryCount(ALIAS)).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({"curated-001,2", "curated-004,2", "curated-007,2", "curated-011,2", "curated-013,5",
            "curated-020,2", "curated-026,2", "curated-031,3", "curated-034,2", "curated-035,2",
            "curated-041,2", "curated-043,2", "curated-045,3", "curated-048,2", "curated-054,2", "curated-069,2", "curated-123,2"})
    void collectionAndAliasGroupsSelectOnlyOneParent(String key, int sourceCount) {
        assertThat(catalog.parents().stream().filter(parent -> parent.canonicalBrandKey().equals(key))).hasSize(1);
        assertThat(parent(key).sources()).hasSize(sourceCount);
    }

    @Test
    void trustedCreedSlugHasEvidenceAndPendingParentsNeverContainGuessedSlugs() {
        assertThat(parent("curated-001").brandSlug()).isEqualTo("creed");
        assertThat(parent("curated-001").verificationEvidence()).isNotBlank();
        assertThat(catalog.unresolvedParents()).allSatisfy(parent -> assertThat(parent.brandSlug()).isNull());
        assertThat(catalog.parents().stream().filter(CuratedBrandDefinition::importable)).allSatisfy(parent -> {
            assertThat(parent.brandSlug()).isNotBlank();
            assertThat(parent.verificationEvidence()).isNotBlank();
        });
        assertThat(parent("curated-185").sources().get(0).entryType()).isEqualTo(BRAND); // Attar Collection is not collapsed by keyword.
        for (String key : List.of("curated-097", "curated-103")) {
            var collectionBrand = parent(key);
            assertThat(collectionBrand.importable()).isTrue();
            assertThat(collectionBrand.sources()).hasSize(1).allSatisfy(source ->
                    assertThat(source.entryType()).isEqualTo(COLLECTION));
            assertThat(catalog.verifiedBrands().stream().filter(brand ->
                    brand.brandSlug().equals(collectionBrand.brandSlug()))).hasSize(1);
            assertThat(catalog.unresolvedParents()).doesNotContain(collectionBrand);
        }
        var edHardy = parent("curated-111");
        assertThat(edHardy.canonicalDisplayName()).isEqualTo("Ed Hardy");
        assertThat(edHardy.resolutionQuery()).isEqualTo("Christian Audigier");
        assertThat(edHardy.sources()).containsExactly(new SourceEntry(111, "에드 하디", BRAND));
        assertThat(catalog.verifiedBrands().stream().filter(brand ->
                brand.brandSlug().equals("christian-audigier"))).hasSize(1);
        assertThat(edHardy.verificationEvidence()).contains("direct get_identity", "brand_name Christian Audigier");
        var dsquared = parent("curated-110");
        assertThat(dsquared.canonicalDisplayName()).isEqualTo("Dsquared2");
        assertThat(dsquared.resolutionQuery()).isEqualTo("Dsquared");
        assertThat(dsquared.verificationEvidence()).contains("MANUAL operator approval", "product-level",
                "dsquared-original-wood", "not a direct get_identity brand confirmation");
    }

    @Test
    void zaraCollaborationStaysExcludedAndManuallyApprovedBrandsRemainIndependent() {
        var collaboration = parent("curated-145");
        assertThat(collaboration.verificationStatus()).isEqualTo(UNRESOLVED);
        assertThat(collaboration.sources().get(0).entryType()).isEqualTo(COLLABORATION);
        assertThat(collaboration.sources().get(0).rawLabel()).isEqualTo("조말론 & 자라");
        assertThat(collaboration.resolutionQuery()).isEqualTo("Zara");
        assertThat(collaboration.brandSlug()).isNull();
        assertThat(collaboration.enabled()).isFalse();
        assertThat(collaboration.importable()).isFalse();
        assertThat(catalog.unresolvedParents()).doesNotContain(collaboration);
        assertThat(parent("curated-031").sources()).noneMatch(source -> source.sourceIndex() == 145);
        assertThat(parent("curated-124").sources().get(0).entryType()).isEqualTo(CuratedBrandDefinition.EntryType.UNRESOLVED);
        assertThat(parent("curated-124").canonicalDisplayName()).isEqualTo("프레드릭 엠");
        assertThat(parent("curated-124").resolutionQuery()).isEqualTo("Frederic M");
        assertThat(parent("curated-124").verificationStatus()).isEqualTo(VERIFIED);
        assertThat(parent("curated-124").brandSlug()).isEqualTo("frederic-m");
        assertThat(parent("curated-124").importable()).isTrue();
        assertThat(parent("curated-125").canonicalDisplayName()).isEqualTo("알레산드로");
        assertThat(parent("curated-125").resolutionQuery()).isEqualTo("Alessandro");
        assertThat(parent("curated-125").verificationStatus()).isEqualTo(VERIFIED);
        assertThat(parent("curated-125").brandSlug()).isEqualTo("alessandro");
        assertThat(parent("curated-125").importable()).isTrue();
        assertThat(parent("curated-003").sources()).hasSize(1);
        assertThat(parent("curated-003").sources()).noneMatch(source -> source.sourceIndex() == 124);
        assertThat(catalog.verifiedBrands()).extracting(brand -> brand.brandSlug())
                .contains("frederic-malle", "frederic-m", "alessandro")
                .doesNotContain("alessandro-dell-acqua", "alessandro-della-torre");
        assertThat(catalog.parents()).noneMatch(parent -> "Zara".equals(parent.canonicalDisplayName()));
    }

    @Test
    void duplicateRawLabelsAndDuplicateVerifiedSlugsProduceOnlyOneImportInConfiguredOrder() {
        var a = definition("a", "same", true, VERIFIED, 1, "Same");
        var b = definition("b", "same", true, VERIFIED, 2, "Same");
        var c = definition("c", "other", true, VERIFIED, 3, "Other");
        var duplicate = new CuratedBrandCatalog(List.of("Same", "Same", "Other"), List.of(a, b, c));
        assertThat(duplicate.rawLabels()).containsExactly("Same", "Same", "Other");
        assertThat(duplicate.verifiedBrands()).extracting(brand -> brand.brandSlug()).containsExactly("same", "other");
    }

    @Test
    void disabledFlagsStatusesAndRetiredSourcesAreExcluded() {
        var filtered = new CuratedBrandCatalog(List.of("A", "B", "C", "D"), List.of(
                definition("a", "a", true, VERIFIED, 1, "A"),
                definition("b", "b", false, VERIFIED, 2, "B"),
                definition("c", "c", true, DISABLED, 3, "C"),
                definition("d", null, true, UNRESOLVED, 4, "D")));
        assertThat(filtered.verifiedBrands()).extracting(brand -> brand.brandSlug()).containsExactly("a");
        assertThat(filtered.disabledCount()).isEqualTo(2);
        assertThat(filtered.unresolvedParents()).hasSize(1);
        for (String key : List.of("curated-090", "curated-096")) {
            var retired = parent(key);
            assertThat(retired.verificationStatus()).isEqualTo(DISABLED);
            assertThat(retired.enabled()).isFalse();
            assertThat(retired.resolutionQuery()).isNull();
            assertThat(retired.brandSlug()).isNull();
            assertThat(retired.importable()).isFalse();
            assertThat(catalog.unresolvedParents()).doesNotContain(retired);
            assertThat(catalog.parents()).contains(retired); // Retain historical source provenance.
        }
        var unavailable = parent("curated-133");
        assertThat(unavailable.verificationStatus()).isEqualTo(DISABLED);
        assertThat(unavailable.enabled()).isFalse();
        assertThat(unavailable.resolutionQuery()).isEqualTo("MIKA LOKKA"); // Retained intended identity, not an active retry.
        assertThat(unavailable.brandSlug()).isNull();
        assertThat(unavailable.importable()).isFalse();
        assertThat(unavailable.sources()).containsExactly(new SourceEntry(133, "미카로카",
                CuratedBrandDefinition.EntryType.UNRESOLVED));
        assertThat(unavailable.verificationEvidence()).contains("Lady Muscat", "NO_USABLE_RESULT");
        assertThat(catalog.unresolvedParents()).doesNotContain(unavailable);
        assertThat(catalog.parents()).contains(unavailable); // Terminal source history is retained.
        assertThat(catalog.verifiedBrands()).hasSize(161);
        assertThat(catalog.unresolvedParents()).isEmpty();
        assertThat(catalog.parents().stream().filter(parent -> parent.verificationStatus() == UNRESOLVED)).hasSize(1);
        assertThat(catalog.parents().stream().filter(parent -> parent.verificationStatus() == DISABLED)).hasSize(3);
        assertThat(catalog.parents()).hasSize(165);
        assertThat(catalog.parents().stream().filter(parent -> parent.verificationStatus() != DISABLED)).hasSize(162);
        assertThat(catalog.disabledCount()).isEqualTo(4);
    }

    @Test
    void plausibleButUnverifiedSlugsAreRejectedAndNeverDerivedFromNames() {
        var unknown = definition("a", null, true, UNRESOLVED, 1, "Plausible Name");
        assertThat(new CuratedBrandCatalog(List.of("Plausible Name"), List.of(unknown)).verifiedBrands()).isEmpty();
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("A"), List.of(definition("a", "a", true, UNRESOLVED, 1, "A"))))
                .isInstanceOf(IllegalArgumentException.class);
        var withoutEvidence = new CuratedBrandDefinition("a", "A", "A", "a", true, VERIFIED, null, null, null, List.of(new SourceEntry(1,"A",BRAND)));
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("A"), List.of(withoutEvidence))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("A"), List.of(definition("a", null, true, VERIFIED, 1, "A"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingChangedOrMultiplyMappedRawOccurrencesAreRejected() {
        var first = definition("a", null, true, UNRESOLVED, 1, "A");
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("A", "B"), List.of(first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("Changed"), List.of(first))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CuratedBrandCatalog(List.of("A"), List.of(first, definition("b", null, true, UNRESOLVED, 1, "A"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CuratedBrandDefinition parent(String key) {
        return catalog.parents().stream().filter(parent -> parent.canonicalBrandKey().equals(key)).findFirst().orElseThrow();
    }

    static CuratedBrandDefinition definition(String key, String slug, boolean enabled,
            CuratedBrandDefinition.VerificationStatus status, int index, String label) {
        return new CuratedBrandDefinition(key, label, label, slug, enabled, status, slug == null ? null : "Test-owned verified evidence",
                null, null, List.of(new SourceEntry(index, label, BRAND)));
    }
}
