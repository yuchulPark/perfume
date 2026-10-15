package com.perfume.ai.embedding;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public class PerfumeEmbeddingText {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Comparator<Integer> POSITION = Comparator.nullsLast(Comparator.naturalOrder());

    public String render(PerfumeEmbeddingDocument document) {
        var lines = new ArrayList<String>();
        add(lines, "Name", document.name());
        add(lines, "Brand", document.brand());
        add(lines, "Description", document.description());
        add(lines, "Release year", document.releaseYear());
        add(lines, "Reviews count", document.reviewsCount());
        for (var layer : List.of("TOP", "MIDDLE", "BASE", "UNLAYERED")) {
            var names = document.notes().stream().filter(note -> layer.equals(note.layer()))
                    .sorted(Comparator.comparing(PerfumeEmbeddingDocument.Note::position, POSITION)
                            .thenComparing(PerfumeEmbeddingDocument.Note::name))
                    .map(note -> clean(note.name())).filter(name -> !name.isEmpty()).toList();
            if (!names.isEmpty()) { add(lines, layer + " notes", String.join(", ", names)); }
        }
        document.accords().stream().sorted(Comparator.comparing(PerfumeEmbeddingDocument.Accord::position, POSITION)
                .thenComparing(PerfumeEmbeddingDocument.Accord::name)).forEach(accord -> {
            var fields = new ArrayList<String>();
            add(fields, "percentage", accord.percentage());
            add(fields, "score", accord.score());
            add(lines, "Accord", clean(accord.name()) + (fields.isEmpty() ? "" : " (" + String.join("; ", fields) + ")"));
        });
        document.metrics().stream().sorted(Comparator.comparing(PerfumeEmbeddingDocument.Metric::key)).forEach(metric -> {
            var fields = new ArrayList<String>();
            add(fields, "score", metric.score());
            add(fields, "category", metric.category());
            add(fields, "records", metric.records());
            add(fields, "reliability", metric.reliability());
            add(fields, "scale", metric.scale());
            add(fields, "derived from", metric.derivedFrom());
            add(fields, "reviews count", metric.reviewsCount());
            add(fields, "label", metric.label());
            if (metric.details() != null && !metric.details().isNull() && !metric.details().isEmpty()) {
                add(fields, "stored details", canonical(metric.details()).toString());
            }
            if (!fields.isEmpty()) { add(lines, "Metric " + metric.key(), String.join("; ", fields)); }
        });
        if (lines.isEmpty()) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_SOURCE); }
        return String.join("\n", lines);
    }

    public String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }

    private static void add(List<String> lines, String label, Object value) {
        if (value == null) { return; }
        var text = value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString() : clean(value.toString());
        if (!text.isEmpty()) { lines.add(label + ": " + text); }
    }

    private static String clean(String value) { return value == null ? "" : WHITESPACE.matcher(value.strip()).replaceAll(" "); }

    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var names = new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> result.add(canonical(child)));
            return result;
        }
        if (node.isNumber()) { return JsonNodeFactory.instance.numberNode(node.decimalValue().stripTrailingZeros()); }
        return node.deepCopy();
    }
}
