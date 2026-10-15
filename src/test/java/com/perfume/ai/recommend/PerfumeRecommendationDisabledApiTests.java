package com.perfume.ai.recommend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = PerfumeRecommendationController.class, properties = "scentrev.brand-import=false")
class PerfumeRecommendationDisabledApiTests {
    @Autowired MockMvc mvc;
    @Test void defaultDisabledEndpointNeedsNoLlmOrSearchBean() throws Exception {
        mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"향수\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("RECOMMEND_DISABLED"));
    }
}
