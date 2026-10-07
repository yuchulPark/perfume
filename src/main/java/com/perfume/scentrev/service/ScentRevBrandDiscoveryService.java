package com.perfume.scentrev.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;

/** Discover one brand completely before persistence. Sequential offset calls, no automatic retries. */
@Service
public class ScentRevBrandDiscoveryService {

    // 10,000 raw rows is deliberately generous for one house; never a catalog-size assertion.
    static final int MAX_PAGES = 1_000;
    private final ScentRevMcpClient client;

    public ScentRevBrandDiscoveryService(ScentRevMcpClient client) {
        this.client = client;
    }

    public ScentRevBrandDiscoveryResult discoverBrandFragrances(String brandSlug) {
        if (brandSlug == null || brandSlug.isBlank() || !brandSlug.equals(brandSlug.strip())) {
            throw new IllegalArgumentException("A canonical nonblank brand slug is required.");
        }
        var unique = new LinkedHashMap<String, Fragrance>();
        Set<List<String>> seenPages = new HashSet<>();
        int offset = 0;
        int pages = 0;
        int rows = 0;
        int duplicates = 0;
        while (true) {
            var progress = snapshot(brandSlug, pages, rows, duplicates, unique);
            ScentRevFilteredSearchResponse page;
            try {
                page = client.searchFragrancesFiltered(brandSlug, offset);
            } catch (RuntimeException exception) {
                String category = exception instanceof ScentRevMcpClientException safe
                        ? " (" + safe.getFailureType() + ")" : "";
                throw ScentRevBrandImportException.discovery(progress, offset, "MCP search failed" + category + ".");
            }
            if (page == null || page.results() == null || page.truncated() == null
                    || page.offset() == null || page.limit() == null) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Missing results or pagination metadata.");
            }
            if (page.offset() != offset || page.limit() != ScentRevMcpClient.SEARCH_PAGE_LIMIT) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Unexpected/non-advancing offset or limit.");
            }
            if (Boolean.TRUE.equals(page.partial()) || page.results().size() > page.limit()
                    || (page.totalReturned() != null && page.totalReturned() != page.results().size())) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Incomplete or inconsistent search page.");
            }
            var slugs = new ArrayList<String>();
            for (Fragrance fragrance : page.results()) {
                if (fragrance == null || fragrance.fragranceSlug() == null || fragrance.fragranceSlug().isBlank()
                        || !fragrance.fragranceSlug().equals(fragrance.fragranceSlug().strip())) {
                    throw ScentRevBrandImportException.discovery(progress, offset, "Missing canonical fragrance slug.");
                }
                if (fragrance.brandSlug() != null && !brandSlug.equals(fragrance.brandSlug())) {
                    throw ScentRevBrandImportException.discovery(progress, offset, "Search result belongs to another brand.");
                }
                slugs.add(fragrance.fragranceSlug());
            }
            // Sorted fingerprints also detect a repeated page with reordered rows.
            List<String> fingerprint = slugs.stream().sorted().toList();
            if (!slugs.isEmpty() && !seenPages.add(fingerprint)) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Repeated search page.");
            }
            int previousUniqueCount = unique.size();
            for (Fragrance fragrance : page.results()) {
                Fragrance existing = unique.putIfAbsent(fragrance.fragranceSlug(), fragrance);
                if (existing != null) {
                    if (existing.publicId() != null && fragrance.publicId() != null
                            && !existing.publicId().equals(fragrance.publicId())) {
                        throw ScentRevBrandImportException.discovery(progress, offset, "Conflicting public IDs for one canonical slug.");
                    }
                    duplicates++;
                }
            }
            pages++;
            rows += page.results().size();
            progress = snapshot(brandSlug, pages, rows, duplicates, unique);
            if (!page.truncated()) {
                return progress; // Includes an empty catalog; never fetch an extra page.
            }
            if (unique.size() == previousUniqueCount) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Truncated search page made no discovery progress.");
            }
            if (pages >= MAX_PAGES) {
                throw ScentRevBrandImportException.discovery(progress, offset, "Single-brand pagination safety limit reached.");
            }
            // Live tool contract says offset + limit, not offset + rows and not next_cursor.
            offset = Math.addExact(page.offset(), page.limit());
        }
    }

    private static ScentRevBrandDiscoveryResult snapshot(String brand, int pages, int rows, int duplicates,
                                                         LinkedHashMap<String, Fragrance> unique) {
        return new ScentRevBrandDiscoveryResult(brand, pages, rows, duplicates, List.copyOf(unique.values()));
    }
}
