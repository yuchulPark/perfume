package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class RecommendationSelectionTests {
    private static final String QUERY = "여름에 지속력이 긴 향수";
    private static final String TWO_PICKS = "{\"picks\":[{\"source\":1,\"factId\":2},{\"source\":2,\"factId\":1}]}";

    @Test void measuresRealBuilderAndOutputSizesAgainstTheSamePreviousFixture() throws Exception {
        var measurements = new LinkedHashMap<String, Integer>();
        for (int count : new int[]{1, 2}) {
            var prompt = builder(count).build(QUERY, snapshots(count));
            var generated = count == 1 ? pick(1, 1) : TWO_PICKS;
            measurements.put("systemBytes", RecommendationEvidenceFormatter.bytes(prompt.messages().get(0).get("content")));
            measurements.put("inputBytes" + count, prompt.inputBytes());
            measurements.put("outputBytes" + count, RecommendationEvidenceFormatter.bytes(generated));
            assertThat(prompt.inputBytes()).isLessThan(count == 1 ? 753 : 1032);
            assertThat(RecommendationEvidenceFormatter.bytes(generated)).isLessThan(count == 1 ? 172 : 333);
            builder(count).validateAndRender(generated, prompt);
        }
        var report = Path.of("target/phase1-validation/rag-selection-after.json");
        Files.createDirectories(report.getParent()); Files.writeString(report, MAPPER.writeValueAsString(measurements));
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void selectsExactlyTheRequestedOriginalFactAndServerSuppliesIdentityFirst(int factId) throws Exception {
        var builder = builder(); var prompt = builder.build(QUERY, snapshots(1));
        var result = builder.validateAndRender(pick(1, factId), prompt);
        assertThat(result).isEqualTo(rendered(prompt, prompt.allowed().get(0).text(), prompt.allowed().get(factId).text()));
        var candidates = MAPPER.readTree(prompt.messages().get(2).get("content").substring("EVIDENCE_DATA ".length())).path("candidates");
        assertThat(candidates).hasSize(1);
        var candidate = candidates.get(0);
        assertThat(candidate.path("name").asText()).isEqualTo(hit(1).name());
        assertThat(candidate.path("brand").asText()).isEqualTo(hit(1).brandName());
        assertThat(candidate.path("facts").get(factId - 1).path("text").asText()).isEqualTo(prompt.allowed().get(factId).text());
        assertThat(candidate.path("facts").get(factId - 1).path("factId").intValue()).isEqualTo(factId);
        assertThat(prompt.messages().get(2).get("content")).doesNotContain(prompt.allowed().get(0).text());
        assertThat(prompt).isEqualTo(builder.build(QUERY, snapshots(1)));
    }
    @Test void twoCandidatesUseTheirOwnExactIdentityAndFactInSearchOrder() {
        var builder = builder(2); var prompt = builder.build(QUERY, snapshots(2));
        assertThat(builder.validateAndRender(TWO_PICKS, prompt)).isEqualTo(rendered(prompt,
                prompt.allowed().get(0).text(), prompt.allowed().get(2).text(), prompt.allowed().get(3).text(), prompt.allowed().get(4).text()));
        // Local ID 1 occurs in both sources but always resolves to that source's own statement.
        assertThat(builder.validateAndRender(pick(2, 1), prompt)).isEqualTo(rendered(prompt, prompt.allowed().get(3).text(), prompt.allowed().get(4).text()));
    }
    @Test void oneFactCandidateIsValidAndIdentityZeroIsNotSelectable() throws Exception {
        var fixture = removeLastChoiceWithinBudget(1);
        assertThat(fixture.prompt().allowed()).hasSize(2);
        assertThat(fixture.builder().validateAndRender(pick(1, 1), fixture.prompt())).contains(fixture.prompt().allowed().get(1).text());
        assertRejected(fixture.builder(), fixture.prompt(), pick(1, 0), RecommendationValidationReason.INVALID_IDENTIFIER);
        assertRejected(fixture.builder(), fixture.prompt(), pick(1, 2), RecommendationValidationReason.UNKNOWN_FACT_ID);
    }
    @Test void anotherSourcesFactIdIsNotResolvedOutsideTheSelectedSourcesWhitelist() throws Exception {
        var fixture = removeLastChoiceWithinBudget(2);
        assertThat(fixture.prompt().allowed()).filteredOn(row -> row.source() == 1 && !row.identity()).hasSize(2);
        assertThat(fixture.prompt().allowed()).filteredOn(row -> row.source() == 2 && !row.identity()).hasSize(1);
        assertThat(fixture.builder().validateAndRender(pick(1, 2), fixture.prompt())).contains("[1]");
        assertRejected(fixture.builder(), fixture.prompt(), pick(2, 2), RecommendationValidationReason.UNKNOWN_FACT_ID);
    }
    @Test void compactModeRejectsTwoPicksAndOmittedSearchCandidates() {
        var prompt = builder().build(QUERY, snapshots(5));
        assertRejected(builder(), prompt, TWO_PICKS, RecommendationValidationReason.INVALID_SELECTION_COUNT);
        assertRejected(builder(), prompt, pick(2, 1), RecommendationValidationReason.UNKNOWN_SOURCE);
    }
    @Test void preservesOriginalSourceNumberInsteadOfRenumberingTheSubset() {
        var prompt = builder().build(QUERY, List.of(evidence(4)));
        assertThat(builder().validateAndRender(pick(4, 1), prompt)).contains("[4]").doesNotContain("[1]");
        assertRejected(builder(), prompt, pick(1, 1), RecommendationValidationReason.UNKNOWN_SOURCE);
    }
    @ParameterizedTest @MethodSource("invalidSelections")
    void fixedReasonsExplainFailureWithoutLeakingProviderValues(String generated, RecommendationValidationReason reason) {
        var prompt = builder(2).build(QUERY, snapshots(2));
        assertRejected(builder(2), prompt, generated, reason);
    }
    private static Stream<Arguments> invalidSelections() {
        return Stream.of(
                Arguments.of((String) null, RecommendationValidationReason.INVALID_JSON),
                Arguments.of("", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("  ", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("{private-response", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("{\"picks\":[", RecommendationValidationReason.INVALID_JSON),
                Arguments.of(pick(1, 1) + " {}", RecommendationValidationReason.INVALID_JSON),
                Arguments.of(pick(1, 1) + " private-answer", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("{\"picks\":[],\"picks\":[]}", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("{\"picks\":[{\"source\":1,\"source\":2,\"factId\":1}]}", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("{\"picks\":[{\"source\":1,\"factId\":1,\"factId\":2}]}", RecommendationValidationReason.INVALID_JSON),
                Arguments.of("null", RecommendationValidationReason.INVALID_SCHEMA),
                Arguments.of("[]", RecommendationValidationReason.INVALID_SCHEMA),
                Arguments.of("true", RecommendationValidationReason.INVALID_SCHEMA),
                Arguments.of("{\"picks\":[1]}", RecommendationValidationReason.INVALID_SCHEMA),
                Arguments.of("{\"picks\":[null]}", RecommendationValidationReason.INVALID_SCHEMA),
                Arguments.of("{}", RecommendationValidationReason.MISSING_REQUIRED_FIELD),
                Arguments.of("{\"picks\":[{}]}", RecommendationValidationReason.MISSING_REQUIRED_FIELD),
                Arguments.of("{\"picks\":[{\"source\":1}]}", RecommendationValidationReason.MISSING_REQUIRED_FIELD),
                Arguments.of("{\"picks\":[{\"factId\":1}]}", RecommendationValidationReason.MISSING_REQUIRED_FIELD),
                Arguments.of("{\"private-field\":1}", RecommendationValidationReason.UNEXPECTED_FIELD),
                Arguments.of("{\"picks\":[],\"answer\":\"private-answer\"}", RecommendationValidationReason.UNEXPECTED_FIELD),
                Arguments.of("{\"picks\":[{\"source\":1,\"factId\":1,\"name\":\"private-perfume\"}]}", RecommendationValidationReason.UNEXPECTED_FIELD),
                Arguments.of("{\"picks\":null}", RecommendationValidationReason.INVALID_FIELD_TYPE),
                Arguments.of("{\"picks\":{}}", RecommendationValidationReason.INVALID_FIELD_TYPE),
                Arguments.of("{\"picks\":\"private-answer\"}", RecommendationValidationReason.INVALID_FIELD_TYPE),
                Arguments.of("{\"picks\":[]}", RecommendationValidationReason.INVALID_SELECTION_COUNT),
                Arguments.of("{\"picks\":[{\"source\":1,\"factId\":1},{\"source\":2,\"factId\":1},{\"source\":3,\"factId\":1}]}", RecommendationValidationReason.INVALID_SELECTION_COUNT),
                Arguments.of(pick(99, 1), RecommendationValidationReason.UNKNOWN_SOURCE),
                Arguments.of(pick(1, 99), RecommendationValidationReason.UNKNOWN_FACT_ID),
                Arguments.of("{\"picks\":[{\"source\":1,\"factId\":1},{\"source\":1,\"factId\":2}]}", RecommendationValidationReason.DUPLICATE_SOURCE),
                Arguments.of("{\"picks\":[{\"source\":2,\"factId\":1},{\"source\":1,\"factId\":2}]}", RecommendationValidationReason.INVALID_SELECTION_ORDER),
                Arguments.of("private-response".repeat(600), RecommendationValidationReason.RESPONSE_TOO_LONG));
    }
    @ParameterizedTest @ValueSource(strings = {"\"private-number\"", "\"1\"", "1.0", "1.5", "1e0", "null", "true", "[]", "{}"})
    void identifiersNeverCoerceTypes(String number) {
        for (String field : List.of("source", "factId")) {
            var json = pick(1, 1).replace("\"" + field + "\":1", "\"" + field + "\":" + number);
            assertRejected(builder(), prompt(), json, RecommendationValidationReason.INVALID_FIELD_TYPE);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "2147483648", "999999999999999999999999"})
    void identifiersMustBePositiveAndFitAnInteger(String number) {
        for (String field : List.of("source", "factId")) {
            var json = pick(1, 1).replace("\"" + field + "\":1", "\"" + field + "\":" + number);
            assertRejected(builder(), prompt(), json, RecommendationValidationReason.INVALID_IDENTIFIER);
        }
    }
    @Test void inconsistentServerEvidenceFailsClosed() {
        var original = prompt();
        var broken = new RecommendationPromptBuilder.Prompt(original.messages(), List.of(original.allowed().get(1)), original.omitted(), original.inputBytes());
        assertRejected(builder(), broken, pick(1, 1), RecommendationValidationReason.EVIDENCE_MISMATCH);
    }
    @Test void logsOnlyFixedReasonAndCountsForAValidJsonWithInvalidFact() {
        var prompt = builder(2).build(QUERY, snapshots(2));
        try (var capture = new Capture()) {
            assertThatThrownBy(() -> builder(2).validateAndRender(pick(1, 99), prompt)).isInstanceOf(RecommendationFailure.class);
            assertThat(capture.appender.list).hasSize(1);
            assertThat(capture.appender.list.get(0).getFormattedMessage()).contains("stage=validation", "validationReason=UNKNOWN_FACT_ID",
                    "optimizationMode=two-candidate", "contextSources=2", "allowedFacts=4", "selectedCount=1", "elapsedMs=")
                    .doesNotContain(QUERY, "Stored", "score:", "factId=99", "source=99", "picks");
        }
    }
    private static void assertRejected(RecommendationPromptBuilder builder, RecommendationPromptBuilder.Prompt prompt,
            String json, RecommendationValidationReason reason) {
        try (var capture = new Capture()) {
            var failure = catchThrowableOfType(RecommendationFailure.class, () -> builder.validateAndRender(json, prompt));
            assertThat(failure.code()).isEqualTo(RecommendationFailure.Code.GENERATION_FAILED);
            assertThat(failure).hasMessage(RecommendationFailure.Code.GENERATION_FAILED.detail()).hasNoCause();
            assertThat(new PerfumeRecommendationExceptionHandler().failure(failure).getProperties()).containsOnly(entry("code", "GENERATION_FAILED"));
            assertThat(capture.appender.list).hasSize(1);
            assertThat(capture.appender.list.get(0).getFormattedMessage()).contains("stage=validation", "validationReason=" + reason.name(),
                    "optimizationMode=", "contextSources=", "allowedFacts=", "selectedCount=", "elapsedMs=")
                    .doesNotContain("private-", "Stored", "Bergamot", QUERY, "score:", "http://");
            assertThat(capture.appender.list.get(0).getThrowableProxy()).isNull();
        }
    }
    private static Fixture removeLastChoiceWithinBudget(int count) throws Exception {
        var query = QUERY + "x".repeat(470);
        assertThat(query.length()).isLessThanOrEqualTo(500);
        var full = builder(count).build(query, snapshots(count));
        var data = MAPPER.readTree(full.messages().get(2).get("content").substring("EVIDENCE_DATA ".length()));
        ((ArrayNode) data.path("candidates").get(count - 1).path("facts")).remove(1);
        int budget = full.inputBytes() - RecommendationEvidenceFormatter.bytes(full.messages().get(2).get("content"))
                + RecommendationEvidenceFormatter.bytes("EVIDENCE_DATA " + MAPPER.writeValueAsString(data));
        assertThat(budget).isBetween(1024, 2816);
        var builder = new RecommendationPromptBuilder(MAPPER, new RecommendationEvidenceFormatter(), budget, count);
        return new Fixture(builder, builder.build(query, snapshots(count)));
    }
    private static String rendered(RecommendationPromptBuilder.Prompt prompt, String... lines) {
        return RecommendationPromptBuilder.INTRO + "\n" + String.join("\n", lines) + "\n" + RecommendationPromptBuilder.LIMITATION
                + (prompt.omitted() ? "\n입력 길이와 안전 제한으로 일부 후보 또는 근거가 생략되었습니다." : "");
    }
    private static List<RecommendationEvidence> snapshots(int count) { return IntStream.rangeClosed(1, count).mapToObj(RecommendationTestSupport::evidence).toList(); }
    private record Fixture(RecommendationPromptBuilder builder, RecommendationPromptBuilder.Prompt prompt) { }
    private static final class Capture implements AutoCloseable {
        private final Logger logger = (Logger) LoggerFactory.getLogger(OllamaRecommendationDiagnostics.class);
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private Capture() { appender.start(); logger.addAppender(appender); }
        @Override public void close() { logger.detachAppender(appender); appender.stop(); }
    }
}
