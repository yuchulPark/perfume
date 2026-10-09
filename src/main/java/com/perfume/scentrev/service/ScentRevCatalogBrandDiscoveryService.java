package com.perfume.scentrev.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.dto.ScentRevBrandListResponse;
import com.perfume.scentrev.dto.ScentRevBrandListResponse.CatalogBrand;

/** list_brands only: independent pagination, no fragrance discovery or persistence. */
@Service
public class ScentRevCatalogBrandDiscoveryService {

    static final int MAX_PAGES = 10_000; // At most 100,000 raw brands, not a catalog-size invariant.
    private static final Pattern CANONICAL_SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private final ScentRevMcpClient client;

    public ScentRevCatalogBrandDiscoveryService(ScentRevMcpClient client) {
        this.client = client;
    }

    public ScentRevCatalogBrandDiscoveryResult discoverBrands() {
        var unique = new LinkedHashMap<String, CatalogBrand>();
        Set<List<String>> seenPages = new HashSet<>();
        int offset = 0;
        int pages = 0;
        int raw = 0;
        int duplicates = 0;
        while (true) {
            ScentRevBrandListResponse page;
            try {
                page = client.listBrands(offset);
            } catch (RuntimeException exception) {
                String category = exception instanceof ScentRevMcpClientException safe
                        ? " (" + safe.getFailureType() + ")" : "";
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "MCP list_brands failed" + category + ".");
            }
            if (page == null || page.brands() == null || page.truncated() == null
                    || page.offset() == null || page.limit() == null) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "Missing brands or pagination metadata.");
            }
            if (page.offset() != offset || page.limit() != ScentRevMcpClient.BRAND_PAGE_LIMIT) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "Unexpected/non-advancing offset or limit.");
            }
            if (page.brands().size() > page.limit()
                    || (page.totalReturned() != null && page.totalReturned() != page.brands().size())) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "Inconsistent brand pagination metadata.");
            }
            var normalized = new ArrayList<CatalogBrand>();
            for (var brand : page.brands()) {
                String slug = brand == null || brand.brandSlug() == null ? "" : brand.brandSlug().strip();
                // Explicit application validation: lowercase ASCII slug segments and the existing VARCHAR bound.
                if (slug.length() > 255 || !CANONICAL_SLUG.matcher(slug).matches()) {
                    throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                            "Blank or malformed canonical brand slug.");
                }
                normalized.add(new CatalogBrand(slug, brand.brandName(), brand.fragranceCount()));
            }
            List<String> fingerprint = normalized.stream().map(CatalogBrand::brandSlug).sorted().toList();
            if (!normalized.isEmpty() && !seenPages.add(fingerprint)) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(), "Repeated brand page.");
            }
            int previousUnique = unique.size();
            for (var brand : normalized) {
                if (unique.putIfAbsent(brand.brandSlug(), brand) != null) {
                    duplicates++;
                }
            }
            raw += normalized.size();
            pages++;
            if (!page.truncated()) {
                return new ScentRevCatalogBrandDiscoveryResult(raw, duplicates, pages, List.copyOf(unique.values()));
            }
            if (unique.size() == previousUnique) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "Truncated brand page made no discovery progress.");
            }
            if (pages >= MAX_PAGES) {
                throw new ScentRevCatalogDiscoveryException(offset, pages, raw, unique.size(),
                        "Catalog brand pagination safety limit reached.");
            }
            // Confirmed list_brands offset contract; never send the optional next_cursor.
            offset = Math.addExact(page.offset(), page.limit());
        }
    }
}
