package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.search.PerfumeSemanticSearchRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(com.perfume.auth.AuthSecurityConfiguration.class)
@WebMvcTest(controllers = PerfumeRecommendationController.class, properties = "scentrev.brand-import=false")
class PerfumeRecommendationApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PerfumeRecommendationService service;
    @ParameterizedTest @ValueSource(strings = {"여름에 좋은 향수", "fresh citrus fragrance"})
    void supportsKoreanAndEnglishAndUsesExistingStrictRequestAndSourceDtos(String query) throws Exception {
        when(service.recommend(any())).thenReturn(PerfumeRecommendationResponse.of("저장된 근거로 확인한 후보입니다. [1]", List.of(hit(1))));
        mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("저장된 근거로 확인한 후보입니다. [1]"))
                .andExpect(jsonPath("$.returnedCount").value(1)).andExpect(jsonPath("$.sources[0].perfumeId").value(1))
                .andExpect(jsonPath("$.sources[0].name").value("Stored 1")).andExpect(jsonPath("$.sources[0].brandName").value("Stored Brand"))
                .andExpect(jsonPath("$.sources[0].similarity").value(1.0))
                .andExpect(jsonPath("$.sources[0].source_text").doesNotExist()).andExpect(jsonPath("$.sources[0].embedding").doesNotExist())
                .andExpect(jsonPath("$.query").doesNotExist()).andExpect(jsonPath("$.picks").doesNotExist())
                .andExpect(jsonPath("$.validationReason").doesNotExist());
        verify(service).recommend(new PerfumeSemanticSearchRequest(query, 5));
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"query\":null}", "{\"query\":\"\"}", "{\"query\":\"   \"}", "{\"query\":\"　 \"}", "{\"query\":7}", "[]", "null", "private-bad-json",
            "{\"query\":\"private-query\",\"topK\":0}", "{\"query\":\"private-query\",\"topK\":21}", "{\"query\":\"private-query\",\"topK\":1.5}",
            "{\"query\":\"private-query\",\"topK\":\"5\"}", "{\"query\":\"private-query\",\"topK\":true}", "{\"query\":\"private-query\",\"topK\":9999999999999999}",
            "{\"query\":\"private-query\",\"unknown\":5}"})
    void invalidJsonAndParametersFailWithoutCallingSearchOrLlm(String json) throws Exception {
        var result = mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-", "Exception", "stackTrace"); verifyNoInteractions(service);
    }
    @Test void enforcesFiveHundredCharacterBoundaryAndTopKDefaultAndBounds() throws Exception {
        mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("query", "향".repeat(501)))))
                .andExpect(status().isBadRequest()); verifyNoInteractions(service);
        when(service.recommend(any())).thenReturn(PerfumeRecommendationResponse.of("근거가 없습니다.", List.of()));
        for (var topK : List.of(1, 20)) {
            mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("query", "향".repeat(500), "topK", topK))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.returnedCount").value(0));
            verify(service).recommend(new PerfumeSemanticSearchRequest("향".repeat(500), topK));
        }
        mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"query\",\"topK\":null}"))
                .andExpect(status().isOk()); verify(service).recommend(new PerfumeSemanticSearchRequest("query", 5));
    }
    @ParameterizedTest @EnumSource(RecommendationFailure.Code.class)
    void errorsStayDistinctAndNeverBecomeAnEmptySuccessfulRecommendation(RecommendationFailure.Code code) throws Exception {
        when(service.recommend(any())).thenThrow(new RecommendationFailure(code));
        mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private-query\"}"))
                .andExpect(status().is(code.status().value())).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.detail").value(code.detail()))
                .andExpect(jsonPath("$.answer").doesNotExist()).andExpect(jsonPath("$.sources").doesNotExist());
    }
    @Test void unexpectedExceptionDetailsNeverEscape() throws Exception {
        when(service.recommend(any())).thenThrow(new IllegalStateException("private-password-sql-prompt-answer http://ollama:11434"));
        var result = mvc.perform(post("/api/ai/perfumes/recommend").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private-query\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("GENERATION_FAILED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-", "ollama", "Exception", "stackTrace");
    }
}
