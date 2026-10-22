package com.perfume.auth;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import com.perfume.ai.recommend.*;
import com.perfume.ai.search.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AuthController.class, PerfumeRecommendationController.class, PerfumeSemanticSearchController.class},
    properties = {"perfume.auth.enabled=false", "scentrev.brand-import=false"})
@Import(AuthSecurityConfiguration.class)
class AuthDisabledApiTests {
    @Autowired MockMvc mvc;
    @MockitoBean PerfumeRecommendationService recommendations;
    @MockitoBean PerfumeSemanticSearchService searches;

    @Test void disabledIdentityAndCsrfDoNotCreateSessionsOrExposeTokens() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.user").isEmpty())
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertNull(result.getRequest().getSession(false)));
    }
    @Test void anonymousRecommendationAndSemanticSearchNeedNeitherSessionNorCsrf() throws Exception {
        when(recommendations.recommend(any())).thenReturn(PerfumeRecommendationResponse.of("Synthetic answer", List.of()));
        when(searches.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of()));
        mvc.perform(post("/api/ai/perfumes/recommend").contentType("application/json").content("{\"query\":\"woody\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("Synthetic answer"));
        mvc.perform(post("/api/ai/perfumes/search").contentType("application/json").content("{\"query\":\"woody\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.returnedCount").value(0));
        verify(recommendations).recommend(new PerfumeSemanticSearchRequest("woody", 5));
        verify(searches).search(new PerfumeSemanticSearchRequest("woody", 5));
    }
}
