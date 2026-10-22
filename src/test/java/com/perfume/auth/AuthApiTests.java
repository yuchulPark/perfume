package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.recommend.*;
import com.perfume.api.controller.*;
import com.perfume.api.dto.*;
import com.perfume.api.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AuthController.class, PerfumeRecommendationController.class, PerfumeController.class, BrandController.class},
    properties = {"perfume.auth.enabled=true", "scentrev.brand-import=false"})
@Import({AuthSecurityConfiguration.class, AuthApiTests.Database.class})
class AuthApiTests {
    private static final String USER = "admin", BASE = "/api/ai/perfumes/recommend", TOKEN = "a".repeat(64);
    @Autowired MockMvc mvc;
    @Autowired AuthUserStore store;
    @Autowired DataSource source;
    @Autowired ObjectMapper json;
    @MockitoBean PerfumeRecommendationService recommendations;
    @MockitoBean PerfumeQueryService perfumes;
    @MockitoBean BrandQueryService brands;

    @TestConfiguration(proxyBeanMethods = false)
    static class Database {
        @Bean DataSource source() {
            var source = new DriverManagerDataSource("jdbc:h2:mem:auth_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
            AuthUserStoreTests.schema(source);
            return source;
        }
        @Bean AuthUserStore store(DataSource source) { return new AuthUserStore(source, new BCryptPasswordEncoder(10)); }
    }
    @BeforeEach void seedIsolatedMemoryDatabase() {
        new JdbcTemplate(source).update("DELETE FROM auth.app_users");
        store.createAdministrator(USER, USER, true); // Development password supplied at runtime, never seeded via SQL.
    }
    private record Browser(MockHttpSession session, String csrf) { }
    private Browser browser(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) request.session(session);
        var result = mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return new Browser((MockHttpSession) result.getRequest().getSession(), json.readTree(result.getResponse().getContentAsString()).get("token").asText());
    }
    private Browser login() throws Exception {
        var browser = browser(null);
        String originalId = browser.session.getId();
        mvc.perform(post("/api/auth/login").session(browser.session).header("X-CSRF-TOKEN", browser.csrf).param("username", USER).param("password", USER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value(USER)).andExpect(jsonPath("$.user.role").value("ADMIN"))
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.user.password_hash").doesNotExist())
            .andExpect(header().doesNotExist("Location"));
        assertNotEquals(originalId, browser.session.getId(), "Login must rotate the pre-authentication session ID");
        return browser(browser.session);
    }
    @Test void loginRestoresMeAndLogoutInvalidatesSessionAndCsrf() throws Exception {
        var before = browser(null);
        var loggedIn = login();
        mvc.perform(get("/api/auth/me").session(loggedIn.session)).andExpect(jsonPath("$.user.username").value(USER));
        mvc.perform(post(BASE).session(loggedIn.session).header("X-CSRF-TOKEN", before.csrf)
            .contentType("application/json").content("{\"query\":\"woody\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(loggedIn.session).header("X-CSRF-TOKEN", loggedIn.csrf))
            .andExpect(status().isOk()).andExpect(header().doesNotExist("Location"));
        assertTrue(loggedIn.session.isInvalid());
        mvc.perform(get(BASE + "/jobs")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        var afterLogout = browser(null);
        assertNotEquals(loggedIn.csrf, afterLogout.csrf);
        mvc.perform(get("/api/auth/me").session(afterLogout.session)).andExpect(jsonPath("$.user").isEmpty());
    }
    @Test void invalidCredentialsHaveSameSafeJsonAndNoRedirect() throws Exception {
        for (String username : List.of(USER, "missing-user")) {
            var browser = browser(null);
            mvc.perform(post("/api/auth/login").session(browser.session).header("X-CSRF-TOKEN", browser.csrf)
                .param("username", username).param("password", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))))
                .andExpect(header().doesNotExist("Location"));
        }
    }
    @Test void everyRecommendationRouteIncludingUnknownDescendantsRequiresSessionWithOrWithoutCsrf() throws Exception {
        var browser = browser(null);
        for (String path : List.of(BASE, BASE + "/jobs", BASE + "/jobs/id", BASE + "/jobs/id/cancel", BASE + "/jobs/id/future")) {
            for (var method : List.of(org.springframework.http.HttpMethod.GET, org.springframework.http.HttpMethod.POST)) {
                mvc.perform(request(method, path)).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED")).andExpect(header().doesNotExist("Location"));
                mvc.perform(request(method, path).session(browser.session).header("X-CSRF-TOKEN", browser.csrf))
                    .andExpect(status().isUnauthorized());
            }
        }
        verifyNoInteractions(recommendations);
    }
    @Test void authenticatedJobRequestsStillForwardCapabilityAndRejectInvalidOwnership() throws Exception {
        var browser = login();
        var running = new RecommendationJobResponse("11111111-1111-4111-8111-111111111111", RecommendationJobResponse.State.RUNNING, null, null, null);
        when(recommendations.start(any(), eq(TOKEN))).thenReturn(running);
        mvc.perform(post(BASE + "/jobs").session(browser.session).header("X-CSRF-TOKEN", browser.csrf).header("X-Recommendation-Token", TOKEN)
            .contentType("application/json").content("{\"query\":\"woody\",\"topK\":5}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("RUNNING"));
        when(recommendations.status(anyString(), isNull())).thenThrow(new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND));
        mvc.perform(get(BASE + "/jobs/unknown").session(browser.session)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND_JOB_NOT_FOUND"));
        when(recommendations.cancel(anyString(), eq(TOKEN))).thenReturn(running);
        mvc.perform(post(BASE + "/jobs/id/cancel").session(browser.session).header("X-CSRF-TOKEN", browser.csrf).header("X-Recommendation-Token", TOKEN))
            .andExpect(status().isAccepted());
        verify(recommendations).cancel("id", TOKEN);
    }
    @Test void missingCsrfBlocksLoginLogoutAndAuthenticatedRecommendationMutations() throws Exception {
        mvc.perform(post("/api/auth/login").param("username", USER).param("password", USER)).andExpect(status().isForbidden());
        var browser = login();
        for (String path : List.of(BASE, BASE + "/jobs", BASE + "/jobs/id/cancel", "/api/auth/logout"))
            mvc.perform(post(path).session(browser.session)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        verifyNoInteractions(recommendations);
    }
    @Test void publicCatalogRemainsAccessibleAndUnknownApiDeniesAuthenticatedUsersWith403() throws Exception {
        when(brands.list()).thenReturn(List.of());
        when(perfumes.list(0, 20, null, null)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true, true));
        when(perfumes.detail(1L)).thenThrow(new PerfumeNotFoundException(1L));
        mvc.perform(get("/api/brands")).andExpect(status().isOk());
        mvc.perform(get("/api/perfumes")).andExpect(status().isOk());
        // A catalog 404 proves the request reached the public detail controller, not a login filter.
        mvc.perform(get("/api/perfumes/1")).andExpect(status().isNotFound());
        mvc.perform(get("/api/private").with(user("viewer").roles("USER"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/auth/signup")).andExpect(status().isForbidden());
    }
}
