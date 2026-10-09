package com.perfume.api.service;

public class PerfumeNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public PerfumeNotFoundException(Long id) { super("Perfume " + id + " was not found."); }
}
