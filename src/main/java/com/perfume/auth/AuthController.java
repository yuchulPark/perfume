package com.perfume.auth;

import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final boolean enabled;
    public AuthController(Environment environment) { enabled = environment.getProperty("perfume.auth.enabled", Boolean.class, false); }

    @GetMapping("/me")
    public ResponseEntity<SessionView> me(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(identity(authentication, enabled));
    }
    @GetMapping("/csrf")
    public ResponseEntity<Map<String, Object>> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(enabled
            ? Map.of("enabled", true, "headerName", token.getHeaderName(), "token", token.getToken())
            : Map.of("enabled", false));
    }
    static SessionView identity(Authentication auth, boolean enabled) {
        boolean loggedIn = enabled && auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
        return new SessionView(enabled, loggedIn ? new UserView(auth.getName(), auth.getAuthorities().stream()
            .map(a -> a.getAuthority()).filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).findFirst().orElse("USER")) : null);
    }
    public record UserView(String username, String role) { }
    public record SessionView(boolean enabled, UserView user) { }
}
