package com.perfume.scentrev.service;

/** Brand discovery fails before imports start; diagnostics contain counts and fixed safe text only. */
public class ScentRevCatalogDiscoveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final int failedOffset;
    private final int pageCount;
    private final int rawBrandCount;
    private final int uniqueBrandCount;

    ScentRevCatalogDiscoveryException(int offset, int pages, int raw, int unique, String reason) {
        super("Failed discovering catalog brands at offset " + offset + " after " + pages
                + " pages (" + raw + " raw, " + unique + " unique brands): " + reason);
        this.failedOffset = offset;
        this.pageCount = pages;
        this.rawBrandCount = raw;
        this.uniqueBrandCount = unique;
    }

    public int getFailedOffset() { return failedOffset; }
    public int getPageCount() { return pageCount; }
    public int getRawBrandCount() { return rawBrandCount; }
    public int getUniqueBrandCount() { return uniqueBrandCount; }
}
