package com.perfume.scentrev.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Reduced schema examples based on the supplied observations, not raw API captures. */
class ScentRevDeserializationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void richProfileDeserializesIdentityAndNotePyramid() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");
        ScentRevIdentity identity = profile.identity();

        assertThat(identity.name()).isEqualTo("Aventus");
        assertThat(identity.publicId()).isEqualTo("fragrance-aventus");
        assertThat(identity.brandName()).isEqualTo("Creed");
        assertThat(identity.brandSlug()).isEqualTo("creed");
        assertThat(identity.fragranceSlug()).isEqualTo("creed-aventus");
        assertThat(identity.description()).isEqualTo("A fruity fragrance with smoky and woody facets.");
        assertThat(identity.releaseYear()).isEqualTo(2010);
        assertThat(identity.reviewsCount()).isEqualTo(26169L);
        assertThat(identity.rating().score()).isEqualTo(new BigDecimal("4.381234567890123456789"));
        assertThat(identity.rating().category()).isEqualTo("highly_rated");
        assertThat(identity.rating().nRecords()).isEqualTo(20000L);
        assertThat(identity.rating().reliability()).isEqualTo("high");
        assertThat(identity.reviewsCount()).isNotEqualTo(identity.rating().nRecords());
        assertThat(identity.gender().scale()).isEqualTo("feminine_to_masculine");
        assertThat(identity.gender().score()).isEqualTo(new BigDecimal("0.89"));
        assertThat(identity.gender().category()).isEqualTo("masculine");
        assertThat(identity.gender().nRecords()).isEqualTo(1240L);

        assertThat(profile.notePyramid().top()).containsExactly("Pineapple", "Bergamot");
        assertThat(profile.notePyramid().middle()).containsExactly("Birch", "Jasmine");
        assertThat(profile.notePyramid().base()).containsExactly("Musk", "Oakmoss");
        assertThat(profile.notePyramid().publicId()).isEqualTo(identity.publicId());
        assertThat(profile.notePyramid().brandSlug()).isEqualTo(identity.brandSlug());
        assertThat(profile.notePyramid().fragranceName()).isEqualTo(identity.name());
        assertThat(profile.notePyramid().fragranceSlug()).isEqualTo(identity.fragranceSlug());
    }

    @Test
    void richProfilePreservesAccordOrderAndIndependentStrengths() throws IOException {
        ScentRevAccords accords = readProfile("rich-profile.json").accords();

        assertThat(accords.publicId()).isEqualTo("fragrance-aventus");
        assertThat(accords.brandSlug()).isEqualTo("creed");
        assertThat(accords.fragranceName()).isEqualTo("Aventus");
        assertThat(accords.fragranceSlug()).isEqualTo("creed-aventus");
        assertThat(accords.accords()).extracting(ScentRevAccords.Accord::name)
                .containsExactly("fruity", "smoky", "woody");
        assertThat(accords.accords()).extracting(ScentRevAccords.Accord::percentage)
                .containsExactly(100, 68, 67);
        assertThat(accords.accords()).extracting(ScentRevAccords.Accord::score)
                .containsExactly(new BigDecimal("1.0"), new BigDecimal("0.68"), new BigDecimal("0.67"));
        assertThat(accords.accords().stream().mapToInt(ScentRevAccords.Accord::percentage).sum()).isEqualTo(235);
    }

    @Test
    void richProfileDeserializesPerfumersAndOptionalPortfolioWithoutNormalizingNan() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");

        assertThat(profile.perfumers()).hasSize(2);
        ScentRevPerfumer perfumer = profile.perfumers().get(0);
        assertThat(perfumer.name()).isEqualTo("Olivier Creed");
        assertThat(perfumer.company()).isEqualTo("Creed");
        assertThat(perfumer.biography()).isEqualTo("A perfumer associated with the house of Creed.");
        assertThat(perfumer.perfumerId()).isEqualTo("p138");
        assertThat(perfumer.perfumesCount()).isEqualTo(120L);
        assertThat(perfumer.otherFragrances()).hasSize(1);

        ScentRevPerfumer.PortfolioFragrance portfolio = perfumer.otherFragrances().get(0);
        assertThat(portfolio.name()).isEqualTo("Green Irish Tweed");
        assertThat(portfolio.publicId()).isEqualTo("fragrance-green-irish-tweed");
        assertThat(portfolio.brandName()).isEqualTo("Creed");
        assertThat(portfolio.fragranceSlug()).isEqualTo("creed-green-irish-tweed");
        assertThat(portfolio.rating().score()).isEqualTo(new BigDecimal("4.12"));
        assertThat(portfolio.rating().category()).isEqualTo("liked");
        assertThat(portfolio.rating().nRecords()).isEqualTo(103L);
        assertThat(portfolio.rating().reliability()).isEqualTo("medium");

        ScentRevPerfumer withoutPortfolio = profile.perfumers().get(1);
        assertThat(withoutPortfolio.perfumerId()).isEqualTo("p139");
        assertThat(withoutPortfolio.company()).isEqualTo("nan");
        assertThat(withoutPortfolio.biography()).isEqualTo("nan");
        assertThat(withoutPortfolio.otherFragrances()).isNull();
    }

    @Test
    void richProfileDeserializesDistinctPerformanceMetricShapes() throws IOException {
        ScentRevPerformance performance = readProfile("rich-profile.json").performance();

        assertThat(performance.name()).isEqualTo("Aventus");
        assertThat(performance.publicId()).isEqualTo("fragrance-aventus");
        assertThat(performance.brandSlug()).isEqualTo("creed");
        assertThat(performance.fragranceSlug()).isEqualTo("creed-aventus");
        assertThat(performance.longevity().score()).isEqualTo(new BigDecimal("0.83"));
        assertThat(performance.longevity().category()).isEqualTo("long_lasting");
        assertThat(performance.longevity().nRecords()).isEqualTo(1432L);
        assertThat(performance.longevity().reliability()).isEqualTo("high");
        assertThat(performance.sillage().score()).isEqualTo(new BigDecimal("0.74"));
        assertThat(performance.sillage().category()).isEqualTo("strong");
        assertThat(performance.sillage().nRecords()).isEqualTo(1301L);
        assertThat(performance.sillage().reliability()).isEqualTo("high");
        assertThat(performance.projection().score()).isEqualTo(new BigDecimal("0.785"));
        assertThat(performance.projection().category()).isEqualTo("noticeable");
        assertThat(performance.projection().nRecords()).isEqualTo(1301L);
        assertThat(performance.projection().reliability()).isEqualTo("high");
        assertThat(performance.projection().derivedFrom()).isEqualTo("longevity_sillage");
        assertThat(performance.timeOfDay().scale()).isEqualTo("day_to_night");
        assertThat(performance.timeOfDay().score()).isEqualTo(new BigDecimal("0.6"));
        assertThat(performance.timeOfDay().category()).isEqualTo("versatile");
        assertThat(performance.timeOfDay().nRecords()).isEqualTo(199L);
    }

    @Test
    void richProfileDeserializesPrimaryAndAllFourSeasons() throws IOException {
        ScentRevPerformance.Season season = readProfile("rich-profile.json").performance().season();

        assertThat(season.scale()).isEqualTo("relative_suitability");
        assertThat(season.primary().name()).isEqualTo("spring");
        assertThat(season.primary().score()).isEqualTo(new BigDecimal("0.94"));
        assertThat(season.primary().category()).isEqualTo("very_suitable");
        assertThat(season.primary().nRecords()).isEqualTo(1211L);
        assertThat(season.bySeason().spring().score()).isEqualTo(new BigDecimal("0.94"));
        assertThat(season.bySeason().spring().category()).isEqualTo("very_suitable");
        assertThat(season.bySeason().spring().nRecords()).isEqualTo(1211L);
        assertThat(season.bySeason().summer().score()).isEqualTo(new BigDecimal("0.81"));
        assertThat(season.bySeason().summer().category()).isEqualTo("suitable");
        assertThat(season.bySeason().summer().nRecords()).isEqualTo(1110L);
        assertThat(season.bySeason().fall().score()).isEqualTo(new BigDecimal("0.89"));
        assertThat(season.bySeason().fall().category()).isEqualTo("suitable");
        assertThat(season.bySeason().fall().nRecords()).isEqualTo(998L);
        assertThat(season.bySeason().winter().score()).isEqualTo(new BigDecimal("0.64"));
        assertThat(season.bySeason().winter().category()).isEqualTo("moderate");
        assertThat(season.bySeason().winter().nRecords()).isEqualTo(875L);
    }

    @Test
    void richProfileDeserializesValueProsConsAndDirectionalSimilarities() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("rich-profile.json");

        assertThat(profile.priceValue().scale()).isEqualTo("poor_to_great");
        assertThat(profile.priceValue().score()).isEqualTo(new BigDecimal("0.61"));
        assertThat(profile.priceValue().category()).isEqualTo("fair");
        assertThat(profile.priceValue().nRecords()).isEqualTo(602L);
        assertThat(profile.priceValue().reliability()).isEqualTo("medium");
        assertThat(profile.prosCons().source()).isEqualTo("community");
        assertThat(profile.prosCons().pros()).extracting(ScentRevProsCons.Item::text)
                .containsExactly("Recognizable fruity opening.");
        assertThat(profile.prosCons().cons()).extracting(ScentRevProsCons.Item::text)
                .containsExactly("Expensive for some reviewers.");

        ScentRevRemindsOf similarities = profile.remindsOf();
        assertThat(similarities.publicId()).isEqualTo("fragrance-aventus");
        assertThat(similarities.fragranceName()).isEqualTo("Aventus");
        assertThat(similarities.fragranceSlug()).isEqualTo("creed-aventus");
        assertThat(similarities.remindsOf()).hasSize(1);
        ScentRevRemindsOf.RelatedFragrance related = similarities.remindsOf().get(0);
        assertThat(related.name()).isEqualTo("Related Fragrance");
        assertThat(related.publicId()).isEqualTo("fragrance-related");
        assertThat(related.fragranceSlug()).isEqualTo("sample-related-fragrance");
        assertThat(related.likeRatio()).isEqualTo(new BigDecimal("0.81234567890123456789"));
    }

    @Test
    void sparseProfilePreservesAbsentMetricsAndEmptyArrays() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("sparse-profile.json");

        assertThat(profile.identity().name()).isEqualTo("Daring");
        assertThat(profile.identity().releaseYear()).isNull();
        assertThat(profile.identity().gender()).isNull();
        assertThat(profile.identity().description()).isNull();
        assertThat(profile.perfumers()).isEmpty();
        assertThat(profile.accords().accords()).isEmpty();
        assertThat(profile.notePyramid().top()).isEmpty();
        assertThat(profile.notePyramid().middle()).isEmpty();
        assertThat(profile.notePyramid().base()).isEmpty();
        assertThat(profile.performance().longevity()).isNull();
        assertThat(profile.performance().sillage()).isNull();
        assertThat(profile.performance().projection()).isNull();
        assertThat(profile.performance().season()).isNull();
        assertThat(profile.performance().timeOfDay()).isNull();
        assertThat(profile.priceValue()).isNull();
        assertThat(profile.prosCons().pros()).isEmpty();
        assertThat(profile.prosCons().cons()).isEmpty();
        assertThat(profile.remindsOf().remindsOf()).isEmpty();
    }

    @Test
    void standaloneNotePyramidPreservesEmptyLayersAndNullFragranceName() throws IOException {
        ScentRevNotePyramid pyramid = readFixture("empty-note-pyramid.json", ScentRevNotePyramid.class);

        assertThat(pyramid.top()).isEmpty();
        assertThat(pyramid.middle()).isEmpty();
        assertThat(pyramid.base()).isEmpty();
        assertThat(pyramid.fragranceName()).isNull();
        assertThat(pyramid.publicId()).isEqualTo("fragrance-wood-sage");
        assertThat(pyramid.brandSlug()).isEqualTo("jo-malone-london");
        assertThat(pyramid.fragranceSlug()).isEqualTo("jo-malone-london-wood-sage-sea-salt");
    }

    @Test
    void dedicatedAppreciationDeserializesIndependentlyFromIdentityRating() throws IOException {
        ScentRevAppreciationResponse response = readFixture("appreciation.json", ScentRevAppreciationResponse.class);
        ScentRevReliableMetric rating = readProfile("rich-profile.json").identity().rating();

        assertThat(response.name()).isEqualTo("Aventus");
        assertThat(response.publicId()).isEqualTo("fragrance-aventus");
        assertThat(response.brandSlug()).isEqualTo("creed");
        assertThat(response.fragranceSlug()).isEqualTo("creed-aventus");
        assertThat(response.appreciation().score()).isEqualTo(new BigDecimal("0.827"));
        assertThat(response.appreciation().category()).isEqualTo("beloved");
        assertThat(response.appreciation().nRecords()).isEqualTo(26169L);
        assertThat(response.appreciation().reliability()).isEqualTo("high");
        assertThat(rating.score()).isEqualTo(new BigDecimal("4.381234567890123456789"));
        assertThat(rating.nRecords()).isEqualTo(20000L);
    }

    @Test
    void explicitNullsStayNullAndLargeCountsDoNotOverflow() throws IOException {
        ScentRevFragranceProfileResponse profile = readProfile("nullable-values.json");

        assertThat(profile.identity().releaseYear()).isNull();
        assertThat(profile.identity().reviewsCount()).isEqualTo(4294967296L);
        assertThat(profile.identity().gender()).isNull();
        assertThat(profile.identity().rating()).isNull();
        assertThat(profile.accords().accords().get(0).percentage()).isNull();
        assertThat(profile.accords().accords().get(0).score()).isNull();
        assertThat(profile.perfumers().get(0).perfumesCount()).isEqualTo(4294967297L);
        assertThat(profile.perfumers().get(0).otherFragrances()).isNull();
        assertThat(profile.notePyramid().top()).isNull();
        assertThat(profile.notePyramid().middle()).isEmpty();
        assertThat(profile.notePyramid().base()).isEmpty();
        assertThat(profile.performance().longevity()).isNotNull();
        assertThat(profile.performance().longevity().score()).isNull();
        assertThat(profile.performance().longevity().nRecords()).isNull();
        assertThat(profile.performance().sillage()).isNull();
        assertThat(profile.performance().projection()).isNull();
        assertThat(profile.performance().timeOfDay().nRecords()).isEqualTo(4294967298L);
        assertThat(profile.performance().timeOfDay().score()).isNull();
        assertThat(profile.performance().timeOfDay().scale()).isEqualTo("provider_defined_scale");
        assertThat(profile.performance().timeOfDay().category()).isEqualTo("new_category");
        assertThat(profile.priceValue()).isNull();
        assertThat(profile.prosCons().pros()).isNull();
        assertThat(profile.prosCons().cons()).isEmpty();
        assertThat(profile.remindsOf().remindsOf()).isNull();
    }

    @Test
    void omittedNumericFieldsRemainNullRatherThanZero() throws IOException {
        ScentRevIdentity identity = objectMapper.readValue("{}", ScentRevIdentity.class);
        ScentRevPerfumer perfumer = objectMapper.readValue("{}", ScentRevPerfumer.class);
        ScentRevReliableMetric metric = objectMapper.readValue("{}", ScentRevReliableMetric.class);
        ScentRevAccords.Accord accord = objectMapper.readValue("{}", ScentRevAccords.Accord.class);

        assertThat(identity.releaseYear()).isNull();
        assertThat(identity.reviewsCount()).isNull();
        assertThat(perfumer.perfumesCount()).isNull();
        assertThat(perfumer.otherFragrances()).isNull();
        assertThat(metric.score()).isNull();
        assertThat(metric.nRecords()).isNull();
        assertThat(accord.percentage()).isNull();
        assertThat(accord.score()).isNull();
    }

    @Test
    void unknownUiAndNestedFieldsAreIgnoredWithoutRelaxingTheObjectMapper() throws IOException {
        assertThat(objectMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();

        JsonNode profileJson = readFixture("rich-profile.json", JsonNode.class);
        addUnknownFields(profileJson);
        ScentRevFragranceProfileResponse profile = objectMapper.treeToValue(
                profileJson, ScentRevFragranceProfileResponse.class);

        assertThat(profile.identity().publicId()).isEqualTo("fragrance-aventus");
        assertThat(profile.perfumers()).hasSize(2);
        assertThat(profile.notePyramid().top()).containsExactly("Pineapple", "Bergamot");
        assertThat(profile.performance().season().bySeason().winter().nRecords()).isEqualTo(875L);

        JsonNode appreciationJson = readFixture("appreciation.json", JsonNode.class);
        addUnknownFields(appreciationJson);
        ScentRevAppreciationResponse appreciation = objectMapper.treeToValue(
                appreciationJson, ScentRevAppreciationResponse.class);
        assertThat(appreciation.appreciation().score()).isEqualTo(new BigDecimal("0.827"));
    }

    private ScentRevFragranceProfileResponse readProfile(String fixture) throws IOException {
        return readFixture(fixture, ScentRevFragranceProfileResponse.class);
    }

    private <T> T readFixture(String fixture, Class<T> type) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/scentrev/" + fixture)) {
            assertThat(stream).as("Fixture %s", fixture).isNotNull();
            return objectMapper.readValue(stream, type);
        }
    }

    private void addUnknownFields(JsonNode node) {
        if (node.isObject()) {
            ((ObjectNode) node).put("future_provider_field", true);
        }
        node.forEach(this::addUnknownFields);
    }
}
