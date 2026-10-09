package com.perfume.scentrev.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.perfume.domain.*;
import com.perfume.repository.*;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** Extends the existing persistence transaction; never calls a provider or deletes rows. */
@Service
public class ScentRevProfileDataService {
    private static final List<String> SECTIONS = List.of("identity", "performance", "appreciation", "notes",
            "note_pyramid", "accords", "perfumers", "pros_cons", "reminds_of", "price_value", "wear_summary");
    private final ObjectMapper mapper;
    private final PerfumeMetricRepository metrics;
    private final PerfumeOpinionRepository opinions;
    private final PerfumeSimilarityRepository similarities;
    private final PerfumeProviderPayloadRepository payloads;
    private final NoteRepository notes;
    private final PerfumeUnlayeredNoteRepository unlayeredNotes;

    public ScentRevProfileDataService(ObjectMapper mapper, PerfumeMetricRepository metrics, PerfumeOpinionRepository opinions,
            PerfumeSimilarityRepository similarities, PerfumeProviderPayloadRepository payloads,
            NoteRepository notes, PerfumeUnlayeredNoteRepository unlayeredNotes) {
        this.mapper = mapper; this.metrics = metrics; this.opinions = opinions; this.similarities = similarities;
        this.payloads = payloads; this.notes = notes; this.unlayeredNotes = unlayeredNotes;
    }

    public static JsonNode body(ScentRevFragranceProfileResponse profile) {
        return profile.rawResponse() != null ? profile.rawResponse() : new ObjectMapper().valueToTree(profile);
    }

    /** Only supplement wear information absent from the full profile; never refetch existing sections. */
    public static boolean needsWearSummary(ScentRevFragranceProfileResponse profile) {
        JsonNode root = body(profile);
        JsonNode performance = root.path("performance");
        JsonNode wear = profile.wearSummary() != null ? profile.wearSummary() : root.path("wear_summary");
        return !(hasMetric(performance.path("season")) || hasMetric(wear.path("season")))
                || !(hasMetric(performance.path("time_of_day")) || hasMetric(wear.path("time_of_day")));
    }

    private static boolean hasMetric(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) { return false; }
        if (node.isObject() && (node.hasNonNull("score") || node.hasNonNull("category") || node.hasNonNull("n_records"))) { return true; }
        for (JsonNode child : node) { if (hasMetric(child)) { return true; } }
        return false;
    }

    /** Pure preflight: validate all section identities and supported numeric shapes before any page writes. */
    public void validate(ScentRevFragranceProfileResponse profile) {
        JsonNode root = body(profile);
        for (String section : SECTIONS) {
            JsonNode value = root.path(section);
            if (value.isObject()) { validateIdentity(profile, value); }
            validateNumbers(value);
        }
        if (profile.wearSummary() != null) { validateWearSummary(profile, profile.wearSummary()); }
        for (JsonNode note : flatNotes(root)) {
            String name = note.isTextual() ? note.asText() : text(note, "name");
            if (name != null && name.strip().length() > 255) { throw new IllegalArgumentException("Unlayered note name exceeds the existing Note column length."); }
        }
        JsonNode related = related(root);
        if (related.isArray()) {
            for (JsonNode item : related) {
                bounded(text(item, "public_id"), 128, "related public ID");
                bounded(text(item, "fragrance_slug"), 255, "related fragrance slug");
            }
        }
    }

    public static void validateWearSummary(ScentRevFragranceProfileResponse profile, JsonNode wear) {
        if (wear == null || !wear.isObject() || (!wear.hasNonNull("public_id") && !wear.hasNonNull("fragrance_slug"))) {
            throw new IllegalArgumentException("Wear summary lacks a confirmable fragrance identity.");
        }
        validateIdentity(profile, wear);
        validateNumbers(wear);
    }

    private static void validateIdentity(ScentRevFragranceProfileResponse profile, JsonNode section) {
        var identity = profile.identity();
        for (var expected : new String[][] {{"public_id", identity.publicId()}, {"fragrance_slug", identity.fragranceSlug()}, {"brand_slug", identity.brandSlug()}}) {
            if (section.hasNonNull(expected[0]) && (!section.get(expected[0]).isTextual()
                    || !Objects.equals(expected[1], section.get(expected[0]).asText()))) {
                throw new IllegalArgumentException("Additional profile section identity conflicts with the selected fragrance.");
            }
        }
    }

    private static void validateNumbers(JsonNode node) {
        if (node == null || !node.isContainerNode()) { return; }
        if (node.isObject()) {
            for (String key : List.of("score", "like_ratio")) {
                if (node.hasNonNull(key) && !node.get(key).isNumber()) { throw new IllegalArgumentException("Provider " + key + " must be numeric or null."); }
            }
            for (String key : List.of("n_records", "reviews_count")) {
                if (node.hasNonNull(key) && (!node.get(key).isIntegralNumber() || !node.get(key).canConvertToLong() || node.get(key).longValue() < 0)) {
                    throw new IllegalArgumentException("Provider " + key + " must be a nonnegative BIGINT or null.");
                }
            }
        }
        for (JsonNode child : node) { validateNumbers(child); }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean save(Perfume perfume, ScentRevFragranceProfileResponse profile) {
        validate(profile);
        JsonNode root = body(profile);
        boolean changed = perfume.updateImageUrl(first(text(root.path("identity"), "image_url"), text(root, "image_url")));
        for (String section : SECTIONS) { changed |= saveMetrics(perfume, section, root.path(section), null); }
        if (profile.wearSummary() != null) { changed |= saveMetrics(perfume, "wear_summary", profile.wearSummary(), null); }
        changed |= saveOpinions(perfume, root.path("pros_cons"));
        changed |= saveSimilarities(perfume, related(root));
        changed |= saveFlatNotes(perfume, flatNotes(root));
        // Only real received JSON is a source snapshot; never fabricate one from a DTO constructor.
        if (profile.rawResponse() != null) { changed |= savePayload(perfume, "get_fragrance_profile", profile.rawResponse()); }
        if (profile.wearSummary() != null) { changed |= savePayload(perfume, "get_wear_summary", profile.wearSummary()); }
        return changed;
    }

    private boolean saveMetrics(Perfume perfume, String path, JsonNode node, String inheritedScale) {
        if (node == null || !node.isContainerNode()) { return false; }
        boolean changed = false;
        String scale = first(text(node, "scale"), inheritedScale);
        if (node.isObject() && path.length() <= 255 && (node.has("score") || node.has("category") || node.has("n_records"))) {
            var found = metrics.findByPerfume_IdAndMetricKey(perfume.getId(), path);
            var row = found.orElseGet(() -> new PerfumeMetric(perfume, path));
            ObjectNode supplied = ((ObjectNode) node).deepCopy();
            if (scale != null && !supplied.hasNonNull("scale")) { supplied.put("scale", scale); }
            JsonNode merged = merge(row.getDetails(), supplied);
            if (found.isEmpty() || !merged.equals(row.getDetails())) {
                row.update(decimal(merged, "score"), text(merged, "category"), count(merged, "n_records"), text(merged, "reliability"),
                        text(merged, "scale"), text(merged, "derived_from"), count(merged, "reviews_count"), text(merged, "name"), merged);
                if (found.isEmpty()) { metrics.save(row); }
                changed = true;
            }
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) { var field = fields.next(); changed |= saveMetrics(perfume, path + "." + field.getKey(), field.getValue(), scale); }
        } else {
            for (int i = 0; i < node.size(); i++) { changed |= saveMetrics(perfume, path + "[" + i + "]", node.get(i), scale); }
        }
        return changed;
    }

    private boolean saveOpinions(Perfume perfume, JsonNode section) {
        boolean changed = false;
        for (var kind : PerfumeOpinion.Kind.values()) {
            JsonNode items = section.path(kind == PerfumeOpinion.Kind.PRO ? "pros" : "cons");
            if (!items.isArray()) { continue; }
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                JsonNode item = items.get(i);
                String value = item.isTextual() ? item.asText() : text(item, "text");
                if (value == null || value.isBlank()) { continue; }
                String hash = sha(value.strip());
                if (!seen.add(hash)) { continue; }
                var found = opinions.findByPerfume_IdAndKindAndTextHash(perfume.getId(), kind, hash);
                var row = found.orElseGet(() -> new PerfumeOpinion(perfume, kind, hash, value.strip()));
                ObjectNode supplied = item.isObject() ? ((ObjectNode) item).deepCopy() : mapper.createObjectNode().put("text", value);
                if (text(section, "source") != null) { supplied.put("source", text(section, "source")); }
                supplied.put("position", i);
                JsonNode merged = merge(row.getDetails(), supplied);
                if (found.isEmpty() || !merged.equals(row.getDetails())) {
                    row.update(text(merged, "source"), i, decimal(merged, "like_ratio"), count(merged, "n_records"), merged);
                    if (found.isEmpty()) { opinions.save(row); }
                    changed = true;
                }
            }
        }
        return changed;
    }

    private boolean saveSimilarities(Perfume perfume, JsonNode items) {
        if (!items.isArray()) { return false; }
        boolean changed = false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject()) { continue; }
            String id = text(item, "public_id"), slug = text(item, "fragrance_slug"), name = text(item, "name");
            String key = id != null ? "id:" + id : slug != null ? "slug:" + slug : name != null ? "name:" + sha(name) : null;
            if (key == null || !seen.add(key)) { continue; }
            var found = similarities.findByPerfume_IdAndReferenceKey(perfume.getId(), key);
            if (found.isEmpty() && slug != null) { found = similarities.findByPerfume_IdAndRelatedFragranceSlug(perfume.getId(), slug); }
            if (found.isPresent() && id != null && found.get().getRelatedPublicId() != null && !id.equals(found.get().getRelatedPublicId())) {
                throw new IllegalArgumentException("Similar-fragrance reference has conflicting public identities.");
            }
            var row = found.orElseGet(() -> new PerfumeSimilarity(perfume, key));
            ObjectNode supplied = ((ObjectNode) item).deepCopy(); supplied.put("position", i);
            JsonNode merged = merge(row.getDetails(), supplied);
            String mergedId = text(merged, "public_id");
            String strongestKey = mergedId != null ? "id:" + mergedId : key;
            if (found.isEmpty() || !merged.equals(row.getDetails()) || !strongestKey.equals(row.getReferenceKey())) {
                row.update(strongestKey, mergedId, text(merged, "fragrance_slug"), text(merged, "name"),
                        decimal(merged, "like_ratio"), count(merged, "n_records"), i, merged);
                if (found.isEmpty()) { similarities.save(row); }
                changed = true;
            }
        }
        return changed;
    }

    private boolean saveFlatNotes(Perfume perfume, JsonNode items) {
        boolean changed = false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            String value = item.isTextual() ? item.asText() : text(item, "name");
            if (value == null || value.isBlank() || !seen.add(value.strip())) { continue; }
            var foundNote = notes.findByName(value.strip());
            var note = foundNote.orElseGet(() -> notes.save(new Note(value.strip())));
            var found = unlayeredNotes.findByPerfume_IdAndNote_Id(perfume.getId(), note.getId());
            if (found.isEmpty()) { unlayeredNotes.save(new PerfumeUnlayeredNote(perfume, note, i)); changed = true; }
            else if (!Objects.equals(found.get().getPosition(), i)) { found.get().updatePosition(i); changed = true; }
        }
        return changed;
    }

    private boolean savePayload(Perfume perfume, String tool, JsonNode raw) {
        String hash = sha(canonical(raw).toString());
        if (payloads.existsByPerfume_IdAndToolNameAndPayloadHash(perfume.getId(), tool, hash)) { return false; }
        payloads.save(new PerfumeProviderPayload(perfume, tool, hash, raw));
        return true;
    }

    /** Missing/null/blank optional fields preserve existing knowledge; source snapshots retain exact responses. */
    private JsonNode merge(JsonNode existing, JsonNode incoming) {
        ObjectNode merged = existing != null && existing.isObject() ? ((ObjectNode) existing).deepCopy() : mapper.createObjectNode();
        var fields = incoming.fields();
        while (fields.hasNext()) {
            var field = fields.next(); JsonNode value = field.getValue();
            if (value.isNull() || (value.isTextual() && value.asText().isBlank())) { continue; }
            merged.set(field.getKey(), value.isObject() ? merge(merged.get(field.getKey()), value) : value.deepCopy());
        }
        return merged;
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = mapper.createObjectNode(); var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, value) -> result.set(key, canonical(value))); return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        if (node.isNumber()) { return mapper.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros()); }
        return node;
    }

    private static JsonNode related(JsonNode root) { JsonNode value = root.path("reminds_of"); return value.isArray() ? value : value.path("reminds_of"); }
    private static JsonNode flatNotes(JsonNode root) {
        JsonNode value = root.path("notes");
        if (value.isArray()) { return value; }
        JsonNode nested = value.path("notes");
        return nested.isArray() ? nested : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    private static String text(JsonNode node, String field) { JsonNode value = node.get(field); return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null; }
    private static String first(String value, String fallback) { return value != null ? value : fallback; }
    private static BigDecimal decimal(JsonNode node, String field) { return node.hasNonNull(field) && node.get(field).isNumber() ? node.get(field).decimalValue() : null; }
    private static Long count(JsonNode node, String field) { return node.hasNonNull(field) ? node.get(field).longValue() : null; }
    private static void bounded(String value, int max, String field) { if (value != null && value.length() > max) { throw new IllegalArgumentException("Provider " + field + " exceeds its mapped column length."); } }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable."); }
    }
}
