package com.perfume.support;

import jakarta.servlet.http.HttpSession;

/** Single-instance MVP bounds: one room per session, 30 new rooms/minute globally, 120 frames/10s/session. */
final class SupportAdmission {
    private long window;
    private int created;
    synchronized void create() {
        long now = System.currentTimeMillis() / 60000;
        if (now != window) { window = now; created = 0; }
        if (++created > 30) throw new SupportFailure(429, "SUPPORT_RATE_LIMIT");
    }
    static void frame(HttpSession session) {
        synchronized (session) {
            String key = SupportAdmission.class.getName();
            long now = System.currentTimeMillis() / 10000;
            long[] bucket = (long[]) session.getAttribute(key);
            if (bucket == null || bucket[0] != now) { bucket = new long[]{now,0}; session.setAttribute(key, bucket); }
            if (++bucket[1] > 120) throw new org.springframework.security.access.AccessDeniedException("SUPPORT_DENIED");
        }
    }
}
