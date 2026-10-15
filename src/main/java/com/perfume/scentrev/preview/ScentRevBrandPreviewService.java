package com.perfume.scentrev.preview;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.preview.ScentRevBrandPreviewResult.StopReason;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Bounded list-only discovery. Never calls the unbounded Import discovery or profile services. */
@Service
@ConditionalOnProperty(name = ScentRevBrandPreviewOptions.PREFIX, havingValue = "true")
public class ScentRevBrandPreviewService {
    private final ScentRevBrandPreviewLocalBrand local;
    private final ScentRevMcpClient client;

    public ScentRevBrandPreviewService(ScentRevBrandPreviewLocalBrand local, ScentRevMcpClient client) {
        this.local = local; this.client = client;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ScentRevBrandPreviewResult preview(ScentRevBrandPreviewOptions options) {
        var progress = new Progress(local.read(options.brandSlug())); // Read transaction ends before any MCP call.
        while (true) {
            if (Thread.currentThread().isInterrupted()) { return progress.result(StopReason.INTERRUPTED, null, null); }
            if (progress.pages >= options.maxPages()) { return progress.result(StopReason.MAX_PAGES, null, null); }
            if (progress.calls >= options.apiCallBudget()) { return progress.result(StopReason.API_BUDGET, null, null); }
            ScentRevFilteredSearchResponse page;
            progress.calls++; // Reserve before calling; failed calls consume this budget too.
            try { page = client.searchFragrancesFiltered(options.brandSlug(), progress.offset); }
            catch (RuntimeException failure) {
                var safe = failure instanceof ScentRevMcpClientException known ? known : null;
                return progress.result(StopReason.API_ERROR, safe == null ? null : safe.getFailureType(),
                        safe == null ? null : safe.getRetryAfterMs());
            }
            try { progress.accept(page); }
            catch (IllegalArgumentException invalid) {
                return progress.result(StopReason.INVALID_PAGE, ScentRevMcpClientException.FailureType.INVALID_RESPONSE, null);
            }
            if (!page.truncated()) { return progress.result(StopReason.COMPLETED, null, null); }
            progress.offset = Math.addExact(page.offset(), page.limit());
        }
    }

    private static class Progress {
        final ScentRevBrandPreviewLocalBrand.Snapshot brand;
        final List<Fragrance> rows = new ArrayList<>();
        final Map<String, String> idsBySlug = new HashMap<>();
        final Set<List<String>> slugPages = new HashSet<>(), identityPages = new HashSet<>();
        int calls, pages, offset, unique, foreign;
        Boolean hasMore;
        Progress(ScentRevBrandPreviewLocalBrand.Snapshot brand) { this.brand = brand; }

        void accept(ScentRevFilteredSearchResponse page) {
            if (page == null || page.results() == null || page.truncated() == null || page.offset() == null || page.limit() == null
                    || page.offset() != offset || page.limit() != ScentRevMcpClient.SEARCH_PAGE_LIMIT
                    || page.results().size() > page.limit() || Boolean.TRUE.equals(page.partial())
                    || (page.totalReturned() != null && page.totalReturned() != page.results().size())) {
                throw new IllegalArgumentException("Invalid preview pagination or partial page.");
            }
            var nextIds = new HashMap<>(idsBySlug);
            for (var row : page.results()) {
                if (row == null || !ScentRevBrandPreviewOptions.canonicalSlug(row.fragranceSlug())
                        || (row.brandSlug() != null && !ScentRevBrandPreviewOptions.canonicalSlug(row.brandSlug()))) {
                    throw new IllegalArgumentException("Invalid preview candidate slug.");
                }
                var id = row.publicId();
                if (id != null) {
                    if (id.isBlank() || id.length() > 128 || !id.equals(id.strip())) {
                        throw new IllegalArgumentException("Invalid preview public ID.");
                    }
                    var previous = nextIds.putIfAbsent(row.fragranceSlug(), id);
                    if (previous != null && !previous.equals(id)) { throw new IllegalArgumentException("Conflicting preview identities."); }
                }
            }
            var slugs = page.results().stream().map(Fragrance::fragranceSlug).sorted().toList();
            var identities = page.results().stream().map(row -> identity(row, nextIds)).sorted().toList();
            if (!slugs.isEmpty() && (slugPages.contains(slugs) || identityPages.contains(identities))) {
                throw new IllegalArgumentException("Repeated preview page.");
            }
            var nextRows = new ArrayList<>(rows); nextRows.addAll(page.results());
            int nextUnique = (int) nextRows.stream().map(row -> identity(row, nextIds)).distinct().count();
            // Newly supplied IDs can merge old candidates; compare both sides using the same identity mapping.
            long previousUnique = rows.stream().map(row -> identity(row, nextIds)).distinct().count();
            if (page.truncated() && nextUnique <= previousUnique) {
                throw new IllegalArgumentException("Truncated preview page made no identity progress.");
            }
            rows.addAll(page.results()); idsBySlug.clear(); idsBySlug.putAll(nextIds);
            slugPages.add(slugs); identityPages.add(identities);
            unique = nextUnique;
            foreign += (int) page.results().stream().filter(row -> row.brandSlug() != null && !brand.brandSlug().equals(row.brandSlug())).count();
            pages++; hasMore = page.truncated();
        }

        private static String identity(Fragrance row, Map<String, String> ids) {
            var id = ids.get(row.fragranceSlug());
            return id == null ? "slug:" + row.fragranceSlug() : "id:" + id;
        }

        ScentRevBrandPreviewResult result(StopReason reason, ScentRevMcpClientException.FailureType failure, Long retryAfterMs) {
            return new ScentRevBrandPreviewResult(brand.brandName(), brand.brandSlug(), brand.dbPerfumeCount(),
                    rows.size(), unique, rows.size() - unique, foreign, pages, calls, offset,
                    reason == StopReason.COMPLETED, hasMore, reason, failure, retryAfterMs);
        }
    }
}
