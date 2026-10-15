package com.perfume.ai.recommend;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Allowlisted measurements only. Never logs a provider string, prompt, model address or response object. */
final class OllamaRecommendationDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(OllamaRecommendationDiagnostics.class);
    private OllamaRecommendationDiagnostics() { }

    static void started(RecommendationPromptBuilder.Prompt prompt, PerfumeRecommendationOptions options) {
        int bytes = prompt.messages().stream().mapToInt(message -> message.get("content").getBytes(StandardCharsets.UTF_8).length).sum();
        log.info("Ollama recommendation diagnostics: event=request, stream=false, format=json, think=false, truncate=false, "
                        + "messageCount={}, messageContentBytes={}, systemBytes={}, queryMessageBytes={}, evidenceMessageBytes={}, "
                        + "selectionFormat=source-fact-id, contextSources={}, allowedSentences={}, omitted={}, optimizationMode={}, maxEvidenceCandidates={}, inputBudgetBytes={}, numCtx={}, maxOutputTokens={}, numThreads=3, "
                        + "readTimeoutMs={}, generationTimeoutMs={}",
                prompt.messages().size(), bytes, messageBytes(prompt, 0), messageBytes(prompt, 1), messageBytes(prompt, 2),
                prompt.allowed().stream().map(RecommendationEvidenceFormatter.Sentence::source).distinct().count(), prompt.allowed().size(), prompt.omitted(),
                options.optimizationMode(), options.maxEvidenceCandidates(), options.inputBudgetBytes(),
                PerfumeRecommendationOptions.CONTEXT_TOKENS, options.maxOutputTokens(), options.readTimeout().toMillis(), options.generationTimeout().toMillis());
    }

    static void validationFailed(RecommendationValidationReason reason, RecommendationPromptBuilder.Prompt prompt,
            int maxCandidates, int selectedCount, long elapsedMs) {
        log.warn("Ollama recommendation diagnostics: event=validation_failed, stage=validation, validationReason={}, "
                        + "optimizationMode={}, contextSources={}, allowedFacts={}, selectedCount={}, elapsedMs={}",
                reason, maxCandidates == 1 ? "compact" : "two-candidate",
                prompt.allowed().stream().map(RecommendationEvidenceFormatter.Sentence::source).distinct().count(),
                prompt.allowed().stream().filter(sentence -> !sentence.identity()).count(),
                selectedCount >= 0 ? selectedCount : "unavailable", elapsedMs);
    }

    static void received(JsonNode response, long elapsedMs, int httpStatus) {
        Long load = number(response, "load_duration"), prompt = number(response, "prompt_eval_duration"), eval = number(response, "eval_duration");
        Long promptCount = number(response, "prompt_eval_count"), evalCount = number(response, "eval_count");
        var content = response.path("message").path("content"); var thinking = response.path("message").path("thinking");
        boolean available = load != null && prompt != null && eval != null && promptCount != null && evalCount != null;
        var reason = response.path("done_reason");
        String doneReason = reason.isTextual() && Set.of("stop", "length", "load", "unload").contains(reason.textValue()) ? reason.textValue() : "unavailable";
        log.info("Ollama recommendation diagnostics: event=response, httpStatus={}, responseComplete=true, elapsedMs={}, "
                        + "done={}, doneReason={}, serverMetricsAvailable={}, total_duration_ns={}, load_duration_ns={}, "
                        + "prompt_eval_duration_ns={}, eval_duration_ns={}, prompt_eval_count={}, eval_count={}, "
                        + "promptTokensPerSecond={}, generatedTokensPerSecond={}, contentChars={}, contentWhitespaceChars={}, thinkingChars={}",
                httpStatus, elapsedMs, response.path("done").isBoolean() ? response.path("done").booleanValue() : "unavailable", doneReason,
                available, value(number(response, "total_duration")), value(load), value(prompt), value(eval), value(promptCount), value(evalCount),
                rate(promptCount, prompt), rate(evalCount, eval), content.isTextual() ? content.textValue().length() : "unavailable",
                content.isTextual() ? content.textValue().codePoints().filter(Character::isWhitespace).count() : "unavailable",
                thinking.isMissingNode() || thinking.isNull() ? 0 : thinking.isTextual() ? thinking.textValue().length() : "unavailable");
    }
    private static int messageBytes(RecommendationPromptBuilder.Prompt prompt, int index) {
        return index < prompt.messages().size() ? prompt.messages().get(index).get("content").getBytes(StandardCharsets.UTF_8).length : 0;
    }
    private static Long number(JsonNode response, String field) {
        var value = response.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }
    private static Object value(Long number) { return number == null ? "unavailable" : number; }
    private static String rate(Long count, Long nanoseconds) {
        return count == null || nanoseconds == null || nanoseconds == 0 ? "unavailable" : String.format(Locale.ROOT, "%.3f", count.doubleValue() * 1_000_000_000 / nanoseconds);
    }
}
