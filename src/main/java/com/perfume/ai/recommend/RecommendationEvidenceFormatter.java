package com.perfume.ai.recommend;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Only embedding-v1 fields become attributed statements. Provider prose is never a system instruction. */
public class RecommendationEvidenceFormatter {
    private static final Pattern UNSAFE = Pattern.compile("(?iu)(ignore.{0,30}(instruction|previous|rule)|system\\s*:|assistant\\s*:|<\\||\\[/?INST\\]|프롬프트|(?:지시|이전).{0,15}무시|비밀번호|api[ _-]?key|\\[\\d+\\])");
    public record Sentence(int source, String text, boolean identity) {
        @Override public String toString() { return "Sentence[source=" + source + ", identity=" + identity + "]"; }
    }
    public record Formatted(RecommendationEvidence evidence, List<Sentence> sentences, boolean omitted) {
        @Override public String toString() { return "Formatted[source=" + evidence.sourceNumber() + ", omitted=" + omitted + "]"; }
    }
    private record Fact(int priority, int order, String label, String value) { }

    public Formatted format(RecommendationEvidence evidence, String query) {
        var lines = evidence.sourceText().lines().toList();
        var source = evidence.source();
        if (lines.stream().filter(line -> line.startsWith("Name: ")).count() != 1
                || lines.stream().filter(line -> line.startsWith("Brand: ")).count() != 1
                || !lines.contains("Name: " + clean(source.name())) || !lines.contains("Brand: " + clean(source.brandName()))
                || unsafe(source.name()) || unsafe(source.brandName())) { throw unavailable(); }
        int number = evidence.sourceNumber();
        var result = new ArrayList<Sentence>();
        result.add(new Sentence(number, clean(source.name()) + " (" + clean(source.brandName()) + ")는 검색된 후보입니다. [" + number + "]", true));
        var facts = new ArrayList<Fact>(); boolean omitted = false;
        int order = 0;
        var lowerQuery = query.toLowerCase(Locale.ROOT);
        boolean wantsProjection = lowerQuery.matches("(?s).*(발향|확산|sillage|projection).*");
        boolean wantsLongevity = lowerQuery.matches("(?s).*(지속|오래|longevity|lasting).*");
        boolean wantsSeason = lowerQuery.matches("(?s).*(여름|겨울|가을|봄|계절|summer|winter|autumn|spring|season).*");
        boolean longevity = false, season = false;
        for (var line : lines) {
            if (line.startsWith("Name: ") || line.startsWith("Brand: ")) { continue; }
            String label = null, value = null; int priority = 5; boolean longevityFact = false, seasonFact = false;
            for (var layer : List.of("TOP", "MIDDLE", "BASE", "UNLAYERED")) {
                if (line.startsWith(layer + " notes: ")) {
                    label = switch (layer) { case "TOP" -> "탑 노트"; case "MIDDLE" -> "미들 노트"; case "BASE" -> "베이스 노트"; default -> "층 구분 없는 노트"; };
                    value = line.substring((layer + " notes: ").length()); priority = 2;
                }
            }
            if (line.startsWith("Accord: ")) { label = "어코드"; value = line.substring(8); priority = 3; }
            if (line.startsWith("Description: ")) { label = "설명"; value = line.substring(13); priority = 6; }
            if (line.startsWith("Release year: ")) { label = "출시 연도"; value = line.substring(14); priority = 7; }
            if (line.startsWith("Metric ")) {
                int split = line.indexOf(": ", 7);
                if (split > 7) {
                    var key = line.substring(7, split); value = line.substring(split + 2);
                    if (!key.matches("[A-Za-z0-9_.-]{1,128}")) { omitted = true; continue; }
                    if (key.contains("longevity")) { label = "지속력 평가 원자료(" + key + ")"; longevityFact = true; priority = wantsLongevity ? 0 : 3; }
                    else if (key.contains("sillage") || key.contains("projection")) { label = "발향력 평가 원자료(" + key + ")"; priority = wantsProjection ? 0 : 3; }
                    else if (key.matches(".*(season|weather|summer|winter|spring|autumn).*")) { label = "계절 평가 원자료(" + key + ")"; seasonFact = true; priority = wantsSeason ? 1 : 3; }
                    else { label = "평가 원자료(" + key + ")"; priority = 5; }
                    int details = value.indexOf("; stored details:");
                    if (details >= 0) { value = value.substring(0, details); omitted = true; }
                }
            }
            if (label == null) { if (!line.isBlank()) { omitted = true; } continue; }
            if (value == null || value.isBlank() || unsafe(value)) { omitted = true; continue; }
            longevity |= longevityFact; season |= seasonFact;
            facts.add(new Fact(priority, order++, label, clean(value)));
        }
        var longevityUncertainty = new Sentence(number, "이 근거에는 지속력 평가가 없어 지속력을 확정할 수 없습니다. [" + number + "]", false);
        var seasonUncertainty = new Sentence(number, "이 근거에는 계절 평가가 없어 계절 적합성을 확정할 수 없습니다. [" + number + "]", false);
        // Missing requested metrics must survive the small evidence budget, ahead of unrelated notes.
        if (wantsLongevity && !longevity) { result.add(longevityUncertainty); }
        if (wantsSeason && !season) { result.add(seasonUncertainty); }
        facts.sort(Comparator.comparingInt(Fact::priority).thenComparingInt(Fact::order));
        if (facts.size() > 24) { facts = new ArrayList<>(facts.subList(0, 24)); omitted = true; }
        for (var fact : facts) {
            var value = shorten(fact.value(), 120);
            boolean cut = !value.equals(fact.value()); omitted |= cut;
            result.add(new Sentence(number, "저장된 " + fact.label() + (cut ? " 일부는 «" : "는 «") + value + "»입니다. [" + number + "]", false));
        }
        if (!wantsLongevity && !longevity) { result.add(longevityUncertainty); }
        if (!wantsSeason && !season) { result.add(seasonUncertainty); }
        if (result.size() == 1) { result.add(new Sentence(number, "제공된 근거만으로 질문의 조건 충족을 확정할 수 없습니다. [" + number + "]", false)); }
        return new Formatted(evidence, List.copyOf(result), omitted);
    }
    static String shorten(String text, int maxBytes) {
        if (bytes(text) <= maxBytes) { return text; }
        var result = new StringBuilder(); int budget = maxBytes - bytes("…");
        for (int point : text.codePoints().toArray()) {
            var next = new String(Character.toChars(point)); int size = bytes(next);
            if (size > budget) { break; }
            result.append(next); budget -= size;
        }
        return result + "…";
    }
    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static boolean unsafe(String value) { return UNSAFE.matcher(value).find() || value.codePoints().anyMatch(c -> Character.isISOControl(c) && !Character.isWhitespace(c)); }
    private static String clean(String value) { return value.strip().replaceAll("(?U)\\s+", " "); }
    private static RecommendationFailure unavailable() { return new RecommendationFailure(RecommendationFailure.Code.EVIDENCE_UNAVAILABLE); }
}
