package com.perfume.ai.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(com.perfume.auth.AuthSecurityConfiguration.class)
@WebMvcTest(controllers = PerfumeSemanticSearchController.class, properties = "scentrev.brand-import=false")
class PerfumeSemanticSearchDisabledApiTests {
    @Autowired MockMvc mvc;
    @Test void disabledIsExplicitWithoutConstructingASearchService() throws Exception {
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"summer\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_DISABLED"));
    }
}
