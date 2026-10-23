package com.perfume.auth;

import java.io.IOException;
import java.util.Arrays;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Explicit exception for this private portfolio VM, never the isolated auth-dev database. */
final class AuthDevelopmentHttp extends OncePerRequestFilter {
    static final String PROFILE = "auth-dev-http";
    static final String ORIGIN = "http://192.168.0.19:8088";
    private final ObjectMapper json;

    AuthDevelopmentHttp(ObjectMapper json) { this.json = json; }

    static boolean enabled(Environment environment) {
        boolean flag = environment.getProperty("perfume.auth.dev-http.enabled", Boolean.class, false);
        boolean profile = environment.matchesProfiles(PROFILE);
        if (!flag && !profile) return false;
        if (!flag || !Arrays.equals(environment.getActiveProfiles(), new String[] {PROFILE})
                || !environment.getProperty("perfume.auth.enabled", Boolean.class, false)
                || !ORIGIN.equals(environment.getProperty("perfume.auth.dev-http.origin"))
                || !"jdbc:postgresql://db:5432/perfume".equals(environment.getProperty("spring.datasource.url"))
                || environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, true)
                || !"PERFUME_DEV_SESSION".equals(environment.getProperty("server.servlet.session.cookie.name"))
                || !environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, false)
                || !"lax".equalsIgnoreCase(environment.getProperty("server.servlet.session.cookie.same-site", ""))
                || !"cookie".equalsIgnoreCase(environment.getProperty("server.servlet.session.tracking-modes", ""))) {
            throw new IllegalStateException("Development HTTP authentication requires the dedicated profile, explicit opt-in, private VM origin/database and protected cookie settings");
        }
        return true;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/auth/") && !AuthSecurityConfiguration.protectedRecommendation(request);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        // The existing Nginx overwrites X-Real-IP and X-Forwarded-Proto. The backend has no host port.
        if (!"192.168.0.19:8088".equals(request.getHeader("Host"))
                || !"http".equals(request.getHeader("X-Forwarded-Proto"))
                || !privateAddress(request.getRemoteAddr()) || !privateAddress(request.getHeader("X-Real-IP"))
                || (origin != null && !ORIGIN.equals(origin))) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean anonymous = authentication == null || authentication instanceof AnonymousAuthenticationToken;
            boolean unauthorized = anonymous && AuthSecurityConfiguration.protectedRecommendation(request);
            AuthSecurityConfiguration.error(json, response, unauthorized ? 401 : 403, unauthorized ? "AUTH_REQUIRED" : "ACCESS_DENIED");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean privateAddress(String address) {
        if (address == null) return false;
        if (address.equals("::1") || address.equals("0:0:0:0:0:0:0:1")) return true;
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) return false;
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) return false;
        }
        return octets[0] == 10 || octets[0] == 127 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
            || (octets[0] == 192 && octets[1] == 168);
    }
}
