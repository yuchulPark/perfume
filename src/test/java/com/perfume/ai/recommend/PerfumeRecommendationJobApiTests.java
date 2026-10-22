package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.context.annotation.Import(com.perfume.auth.AuthSecurityConfiguration.class)
@WebMvcTest(controllers = PerfumeRecommendationController.class, properties = "scentrev.brand-import=false")
class PerfumeRecommendationJobApiTests {
    static final String BASE = "/api/ai/perfumes/recommend/jobs", ID = "11111111-1111-4111-8111-111111111111", TOKEN = "d".repeat(64);
    @Autowired MockMvc mvc;
    @MockitoBean PerfumeRecommendationService service;
    @Test void createsAndPollsAndCancelsWithCapabilitiesAndNoStoreWithoutEchoingRequests() throws Exception {
        var running = new RecommendationJobResponse(ID, RecommendationJobResponse.State.RUNNING, null, null, null);
        when(service.start(any(), eq(TOKEN))).thenReturn(running);
        mvc.perform(post(BASE).header("X-Recommendation-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"private-question\",\"topK\":5}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.jobId").value(ID)).andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.query").doesNotExist()).andExpect(jsonPath("$.token").doesNotExist());
        var complete = new RecommendationJobResponse(ID, RecommendationJobResponse.State.SUCCEEDED, PerfumeRecommendationResponse.of("저장된 정보 [1]", List.of(hit(1))), null, null);
        when(service.status(ID, TOKEN)).thenReturn(complete);
        mvc.perform(get(BASE + "/" + ID).header("X-Recommendation-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.result.answer").value("저장된 정보 [1]")).andExpect(jsonPath("$.result.sources[0].perfumeId").value(1));
        when(service.find(TOKEN)).thenReturn(running);
        mvc.perform(get(BASE).header("X-Recommendation-Token", TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value(ID));
        when(service.cancel(ID, TOKEN)).thenReturn(new RecommendationJobResponse(ID, RecommendationJobResponse.State.CANCELLING, null, "RECOMMEND_INTERRUPTED", 503));
        mvc.perform(post(BASE + "/" + ID + "/cancel").header("X-Recommendation-Token", TOKEN))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("CANCELLING"));
        verify(service).start(new com.perfume.ai.search.PerfumeSemanticSearchRequest("private-question", 5), TOKEN);
    }
    @Test void unknownOrInaccessibleJobHasOneFixedNotFoundProblem() throws Exception {
        when(service.status(anyString(), any())).thenThrow(new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND));
        when(service.cancel(anyString(), any())).thenThrow(new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_JOB_NOT_FOUND));
        mvc.perform(get(BASE + "/unknown")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND_JOB_NOT_FOUND"));
        mvc.perform(post(BASE + "/unknown/cancel")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECOMMEND_JOB_NOT_FOUND"));
    }
    @Test void invalidQuestionStillUsesTheStrictOriginalDtoAndBusyIsNotAccepted() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
        when(service.start(any(), any())).thenThrow(new RecommendationFailure(RecommendationFailure.Code.RECOMMEND_BUSY));
        mvc.perform(post(BASE).header("X-Recommendation-Token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"query\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RECOMMEND_BUSY"));
    }
}
