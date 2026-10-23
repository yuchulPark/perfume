package com.perfume.auth;

import java.io.IOException;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthSecurityConfiguration {
    public static final String RECOMMEND = "/api/ai/perfumes/recommend";

    @Bean PasswordEncoder authPasswordEncoder() { return new BCryptPasswordEncoder(12); }

    // Also suppress Boot's generated default user when the staged feature is disabled.
    @Bean UserDetailsService authUsers(ObjectProvider<AuthUserStore> stores) {
        return username -> {
            var store = stores.getIfAvailable();
            if (store == null) throw new UsernameNotFoundException("Invalid credentials");
            return store.loadUser(username);
        };
    }

    @Bean SecurityFilterChain authSecurity(HttpSecurity http, Environment env, ObjectMapper json) throws Exception {
        boolean enabled = env.getProperty("perfume.auth.enabled", Boolean.class, false);
        if (AuthDevelopmentHttp.enabled(env)) http.addFilterBefore(new AuthDevelopmentHttp(json), CsrfFilter.class);
        http.requestCache(cache -> cache.disable())
            .httpBasic(basic -> basic.disable())
            .csrf(csrf -> csrf.requireCsrfProtectionMatcher(request -> enabled
                && CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)
                // Existing semantic search is read-only and explicitly public.
                && !(request.getMethod().equals("POST") && applicationPath(request).equals("/api/ai/perfumes/search"))))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> error(json, res, 401, "AUTH_REQUIRED"))
                .accessDeniedHandler((req, res, ex) -> {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    boolean anonymous = auth == null || auth instanceof AnonymousAuthenticationToken;
                    error(json, res, anonymous && protectedRecommendation(req) ? 401 : 403,
                        anonymous && protectedRecommendation(req) ? "AUTH_REQUIRED" : "ACCESS_DENIED");
                }));
        if (!enabled) {
            http.authorizeHttpRequests(rules -> rules.anyRequest().permitAll())
                .formLogin(form -> form.disable()).logout(logout -> logout.disable());
        } else {
            http.authorizeHttpRequests(rules -> rules
                .requestMatchers(RECOMMEND, RECOMMEND + "/**").authenticated()
                .requestMatchers("/api/auth/csrf", "/api/auth/me", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/perfumes", "/api/perfumes/**", "/api/brands", "/api/brands/**", "/healthz").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/ai/perfumes/search").permitAll()
                .requestMatchers("/api/**").denyAll()
                .anyRequest().permitAll())
                .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/api/auth/login")
                    .successHandler((req, res, auth) -> write(json, res, 200, AuthController.identity(auth, true)))
                    .failureHandler((req, res, ex) -> error(json, res, 401, "INVALID_CREDENTIALS")))
                .logout(logout -> logout.logoutUrl("/api/auth/logout").invalidateHttpSession(true)
                    .clearAuthentication(true).deleteCookies(env.getProperty("server.servlet.session.cookie.name", "JSESSIONID"))
                    .logoutSuccessHandler((req, res, auth) -> write(json, res, 200, Map.of("success", true))));
        }
        return http.build();
    }

    static boolean protectedRecommendation(HttpServletRequest request) {
        String path = applicationPath(request);
        return path.equals(RECOMMEND) || path.startsWith(RECOMMEND + "/");
    }
    private static String applicationPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
    static void error(ObjectMapper json, HttpServletResponse response, int status, String code) throws IOException {
        write(json, response, status, Map.of("status", status, "code", code));
    }
    static void write(ObjectMapper json, HttpServletResponse response, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        json.writeValue(response.getWriter(), value);
    }
}
