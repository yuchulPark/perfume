package com.perfume.support;

public final class SupportFailure extends RuntimeException {
    private final int status;
    private final String code;
    public SupportFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    static SupportFailure missing() { return new SupportFailure(404, "SUPPORT_NOT_FOUND"); }
}
