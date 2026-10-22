package com.perfume.ai.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@WebMvcTest(controllers = PerfumeSemanticSearchController.class, properties = "scentrev.brand-import=false")
class PerfumeSemanticSearchApiTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean PerfumeSemanticSearchService service;

    @ParameterizedTest
    @ValueSource(strings = {"여름에 좋은 시원한 남자 향수", "fresh citrus for summer"})
    void handlesKoreanAndEnglishAndDefaultsTopKWithoutExposingSources(String query) throws Exception {
        when(service.search(new PerfumeSemanticSearchRequest(query, 5))).thenReturn(PerfumeSemanticSearchResponse.of(List.of(
                new PerfumeSemanticSearchResponse.Hit(3, "Stored perfume", "Stored brand", 0.85))));
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.returnedCount").value(1))
                .andExpect(jsonPath("$.results[0].perfumeId").value(3)).andExpect(jsonPath("$.results[0].similarity").value(0.85))
                .andExpect(jsonPath("$.results[0].embedding").doesNotExist()).andExpect(jsonPath("$.results[0].source_text").doesNotExist())
                .andExpect(jsonPath("$.query").doesNotExist());
        verify(service).search(new PerfumeSemanticSearchRequest(query, 5));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void acceptsTopKBoundsAndReturnsNormalEmptyResults(int topK) throws Exception {
        when(service.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of()));
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("query", "query", "topK", topK))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results").isEmpty()).andExpect(jsonPath("$.returnedCount").value(0));
        verify(service).search(new PerfumeSemanticSearchRequest("query", topK));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"query\":null}", "{\"query\":\"\"}", "{\"query\":\"   \":}",
            "{\"query\":\"   \"}", "{\"query\":\"　 \"}", "{\"query\":7}", "[]", "null", "broken-private-json",
            "{\"query\":\"private-query\",\"topK\":0}", "{\"query\":\"private-query\",\"topK\":21}",
            "{\"query\":\"private-query\",\"topK\":1.5}", "{\"query\":\"private-query\",\"topK\":\"5\"}",
            "{\"query\":\"private-query\",\"topK\":true}", "{\"query\":\"private-query\",\"topK\":9999999999999999}",
            "{\"query\":\"private-query\",\"topk\":5}"})
    void rejectsMalformedAndInvalidRequestsWithoutEchoingValuesOrCallingTheService(String json) throws Exception {
        var result = mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-query", "broken-private-json", "Exception", "stackTrace");
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOverlongInputAndAcceptsTheExactLengthBoundary() throws Exception {
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("query", "가".repeat(501)))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.search(any())).thenReturn(PerfumeSemanticSearchResponse.of(List.of()));
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("query", "가".repeat(500)))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @EnumSource(SemanticSearchFailure.Code.class)
    void returnsDistinctSafeProblemCodesInsteadOfHidingFailuresAsEmptySearches(SemanticSearchFailure.Code code) throws Exception {
        when(service.search(any())).thenThrow(new SemanticSearchFailure(code));
        mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private-text\"}"))
                .andExpect(status().is(code.status().value())).andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.results").doesNotExist()).andExpect(jsonPath("$.detail").value(code.detail()));
    }

    @Test
    void unexpectedInternalDetailsStayPrivate() throws Exception {
        when(service.search(any())).thenThrow(new IllegalStateException("private-password-query-vector-sql-response"));
        var result = mvc.perform(post("/api/ai/perfumes/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private-text\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SEARCH_FAILED")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-", "Exception", "sql", "stackTrace");
    }
}
