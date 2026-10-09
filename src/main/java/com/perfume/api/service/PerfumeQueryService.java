package com.perfume.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.JsonNode;
import com.perfume.api.dto.*;
import com.perfume.api.dto.PerfumeDetailResponse.*;
import com.perfume.domain.NoteLayer;
import com.perfume.domain.PerfumeNote;
import com.perfume.domain.PerfumeOpinion;
import com.perfume.repository.*;

/** Local, read-only catalog access. No dependency on any provider client or importer. */
@Service
@Transactional(readOnly = true)
public class PerfumeQueryService {
    private final PerfumeRepository perfumes;
    private final PerfumeNoteRepository notes;
    private final PerfumeUnlayeredNoteRepository flatNotes;
    private final PerfumeAccordRepository accords;
    private final PerfumePerfumerRepository perfumers;
    private final PerfumeMetricRepository metrics;
    private final PerfumeOpinionRepository opinions;
    private final PerfumeSimilarityRepository similarities;

    public PerfumeQueryService(PerfumeRepository perfumes, PerfumeNoteRepository notes,
            PerfumeUnlayeredNoteRepository flatNotes, PerfumeAccordRepository accords,
            PerfumePerfumerRepository perfumers, PerfumeMetricRepository metrics,
            PerfumeOpinionRepository opinions, PerfumeSimilarityRepository similarities) {
        this.perfumes = perfumes; this.notes = notes; this.flatNotes = flatNotes; this.accords = accords;
        this.perfumers = perfumers; this.metrics = metrics; this.opinions = opinions; this.similarities = similarities;
    }

    public PageResponse<PerfumeSummaryResponse> list(int page, int size, String query, String brandSlug) {
        if (page < 0 || size < 1 || size > 100) { throw new IllegalArgumentException("Page must be nonnegative and size must be between 1 and 100."); }
        var rows = perfumes.findCatalogPage(pattern(query), brandSlug, PageRequest.of(page, size));
        return PageResponse.from(rows.map(row -> new PerfumeSummaryResponse(row.getId(), row.getPublicId(),
                row.getFragranceSlug(), row.getName(), row.getReleaseYear(), row.getImageUrl(),
                new BrandResponse(row.getBrandId(), row.getBrandName(), row.getBrandSlug()))));
    }

    public PerfumeDetailResponse detail(Long id) {
        var perfume = perfumes.findDetailById(id).orElseThrow(() -> new PerfumeNotFoundException(id));
        var brand = perfume.getBrand(); // Fetched with the perfume, not a lazy follow-up query.
        var layered = notes.findDetailNotes(id);
        var noteData = new NotesResponse(layer(layered, NoteLayer.TOP), layer(layered, NoteLayer.MIDDLE),
                layer(layered, NoteLayer.BASE), flatNotes.findDetailNotes(id).stream()
                        .map(row -> new NoteResponse(row.getNote().getId(), row.getNote().getName(), row.getPosition())).toList());
        var accordData = accords.findDetailAccords(id).stream().map(row -> new AccordResponse(row.getAccord().getId(),
                row.getAccord().getName(), row.getPercentage(), row.getScore(), row.getPosition())).toList();
        var perfumerData = perfumers.findDetailPerfumers(id).stream().map(row -> {
            var perfumer = row.getPerfumer();
            return new PerfumerResponse(perfumer.getId(), perfumer.getScentrevPerfumerId(), perfumer.getName(),
                    perfumer.getCompany(), perfumer.getBiography(), perfumer.getPerfumesCount());
        }).toList();
        var metricData = new LinkedHashMap<String, MetricResponse>();
        for (var row : metrics.findByPerfume_IdOrderByMetricKeyAsc(id)) {
            metricData.put(row.getMetricKey(), new MetricResponse(row.getScore(), row.getCategory(), row.getNRecords(),
                    row.getReliability(), row.getScale(), row.getDerivedFrom(), row.getReviewsCount(), row.getLabel(), copy(row.getDetails())));
        }
        var opinionRows = opinions.findByPerfume_IdOrderByPositionAscIdAsc(id);
        var similarityData = similarities.findByPerfume_IdOrderByPositionAscIdAsc(id).stream()
                .map(row -> new SimilarityResponse(row.getRelatedPublicId(), row.getRelatedFragranceSlug(), row.getRelatedName(),
                        row.getLikeRatio(), row.getNRecords(), row.getPosition(), copy(row.getDetails()))).toList();
        return new PerfumeDetailResponse(perfume.getId(), perfume.getScentrevPublicId(), perfume.getFragranceSlug(), perfume.getName(),
                brand == null ? null : new BrandResponse(brand.getId(), brand.getName(), brand.getBrandSlug()),
                perfume.getDescription(), perfume.getReleaseYear(), perfume.getImageUrl(), perfume.getReviewsCount(), noteData,
                accordData, perfumerData, metricData, opinion(opinionRows, PerfumeOpinion.Kind.PRO),
                opinion(opinionRows, PerfumeOpinion.Kind.CON), similarityData);
    }

    private static List<NoteResponse> layer(List<PerfumeNote> rows, NoteLayer layer) {
        return rows.stream().filter(row -> row.getLayer() == layer)
                .map(row -> new NoteResponse(row.getNote().getId(), row.getNote().getName(), row.getPosition())).toList();
    }
    private static List<OpinionResponse> opinion(List<PerfumeOpinion> rows, PerfumeOpinion.Kind kind) {
        return rows.stream().filter(row -> row.getKind() == kind).map(row -> new OpinionResponse(row.getText(), row.getSource(),
                row.getPosition(), row.getLikeRatio(), row.getNRecords(), copy(row.getDetails()))).toList();
    }
    private static JsonNode copy(JsonNode value) { return value == null ? null : value.deepCopy(); }
    private static String pattern(String query) {
        if (query == null || query.isBlank()) { return null; }
        return "%" + query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
