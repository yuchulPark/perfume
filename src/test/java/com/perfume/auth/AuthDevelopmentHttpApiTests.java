package com.perfume.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Existing API/CSRF/session tests run again behind the development HTTP boundary. */
@ActiveProfiles("auth-dev-http")
@TestPropertySource(properties={"perfume.auth.dev-http.enabled=true", "perfume.auth.dev-http.origin=http://192.168.0.19:8088", "spring.datasource.url=jdbc:postgresql://db:5432/perfume"})
@Import(AuthDevelopmentHttpApiTests.Proxy.class)
class AuthDevelopmentHttpApiTests extends AuthApiTests {
    @TestConfiguration(proxyBeanMethods=false)
    static class Proxy {
        @Bean MockMvcBuilderCustomizer privateProxy() {
            return builder -> builder.defaultRequest(get("/").header("Host", "192.168.0.19:8088")
                .header("X-Real-IP", "192.168.0.50").header("X-Forwarded-Proto", "http"));
        }
    }
    @Test void publicClientWrongHostAndCrossOriginCannotAcquireOrUseAuthentication() throws Exception {
        mvc.perform(get("/api/auth/csrf").header("X-Real-IP", "8.8.8.8")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("Host", "public.example.com:8088")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("Origin", "http://public.example.com")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").header("X-Forwarded-Proto", "https")).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/csrf").with(request -> { request.setRemoteAddr("8.8.8.8"); return request; }))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/perfumes/recommend/jobs").header("X-Real-IP", "8.8.8.8"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        // Public catalog and health routes do not gain a login requirement.
        mvc.perform(get("/api/brands").header("X-Real-IP", "8.8.8.8")).andExpect(status().isOk());
    }
}
