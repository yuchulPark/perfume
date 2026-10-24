package com.perfume.support;

import java.time.Instant;
import java.util.UUID;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Identity is server-owned and survives Spring's changeSessionId login protection. */
public final class SupportSessions {
    static final String VISITOR = SupportSessions.class.getName() + ".visitor";
    static final String ROUTE = SupportSessions.class.getName() + ".route";
    static final String HTTP = SupportSessions.class.getName() + ".http";
    private SupportSessions() { }
    public static boolean alive(HttpSession session) {
        if (session == null) return false;
        try {
            session.getAttribute(VISITOR); // Throws after explicit logout / container expiration.
            return session.getMaxInactiveInterval() <= 0 || Instant.now().toEpochMilli() - session.getLastAccessedTime()
                < session.getMaxInactiveInterval() * 1000L;
        } catch (IllegalStateException expired) { return false; }
    }
    public static UUID visitor(HttpSession session) {
        if (!alive(session)) throw new SupportFailure(401, "SUPPORT_SESSION_EXPIRED");
        synchronized (session) {
            var key = (UUID) session.getAttribute(VISITOR);
            if (key == null) { key = UUID.randomUUID(); session.setAttribute(VISITOR, key); }
            return key;
        }
    }
    static String route(HttpSession session) {
        visitor(session);
        synchronized (session) {
            var value = (String) session.getAttribute(ROUTE);
            if (value == null) { value = UUID.randomUUID().toString(); session.setAttribute(ROUTE, value); }
            return value;
        }
    }
    public static boolean admin(HttpSession session) {
        if (!alive(session)) return false;
        try {
            var context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            Authentication auth = context instanceof SecurityContext security ? security.getAuthentication() : null;
            return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        } catch (IllegalStateException expired) { return false; }
    }
    public static void requireAdmin(HttpSession session) {
        if (!alive(session)) throw new SupportFailure(401, "SUPPORT_SESSION_EXPIRED");
        if (!admin(session)) throw new SupportFailure(403, "SUPPORT_DENIED");
    }
}
