package com.perfume.api.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.perfume.api.dto.*;
import com.perfume.api.dto.PerfumeDetailResponse.*;
import com.perfume.api.service.*;

/** Real MVC routing, validation and JSON serialization; no repository/JDBC/MCP beans are loaded. */
@WebMvcTest(controllers = {BrandController.class, PerfumeController.class}, properties = "scentrev.brand-import=false")
class CatalogApiTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private BrandQueryService brands;
    @MockitoBean private PerfumeQueryService perfumes;

    @Test
    void listsBrandsAsDtoArray() throws Exception {
        when(brands.list()).thenReturn(List.of(new BrandResponse(1L, "Kierin NYC", "kierin")));
        mvc.perform(get("/api/brands")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(1)).andExpect(jsonPath("$[0].name").value("Kierin NYC"))
                .andExpect(jsonPath("$[0].brandSlug").value("kierin"));
        verifyNoInteractions(perfumes);
    }

    @Test
    void listsPerfumesWithDefaultPageSizeTwentyAndStableMetadata() throws Exception {
        when(perfumes.list(0, 20, null, null)).thenReturn(new PageResponse<>(List.of(
                new PerfumeSummaryResponse(2L, "provider-id", "creed-aventus", "Aventus", null, null,
                        new BrandResponse(1L, "Creed", "creed"))), 0, 20, 41, 3, true, false));
        mvc.perform(get("/api/perfumes")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalElements").value(41))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.content[0].brand.name").value("Creed"))
                .andExpect(jsonPath("$.content[0].releaseYear").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.content[0].imageUrl").value(org.hamcrest.Matchers.nullValue()));
        verify(perfumes).list(0, 20, null, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/perfumes", "/api/perfumes/search"})
    void bothListingAndSearchRoutesAcceptKeywordBrandAndPagination(String route) throws Exception {
        when(perfumes.list(1, 5, "Kierin NYC", "kierin")).thenReturn(new PageResponse<>(List.of(), 1, 5, 0, 0, false, true));
        mvc.perform(get(route).param("page", "1").param("size", "5").param("q", "Kierin NYC").param("brandSlug", "kierin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        verify(perfumes).list(1, 5, "Kierin NYC", "kierin");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101", "page=abc", "brandSlug=bad,slug", "brandSlug="})
    void invalidInputsReturnFourHundredBeforeTheService(String query) throws Exception {
        String[] parts = query.split("=", 2);
        mvc.perform(get("/api/perfumes").param(parts[0], parts[1])).andExpect(status().isBadRequest());
        verifyNoInteractions(perfumes);
    }

    @Test
    void overlongSearchIsRejectedBeforeTheService() throws Exception {
        mvc.perform(get("/api/perfumes/search").param("q", "a".repeat(256))).andExpect(status().isBadRequest());
        verifyNoInteractions(perfumes);
    }

    @Test
    void detailSerializesNullsEmptyChildrenAndStoredEvaluationPaths() throws Exception {
        var rating = new MetricResponse(new BigDecimal("0.91"), null, null, null, null, null, null, null, null);
        when(perfumes.detail(2L)).thenReturn(new PerfumeDetailResponse(2L, "provider-id", "creed-aventus", "Aventus",
                new BrandResponse(1L, "Creed", "creed"), null, null, null, null,
                new NotesResponse(List.of(), List.of(), List.of(), List.of()), List.of(), List.of(),
                Map.of("identity.rating", rating), List.of(), List.of(), List.of()));
        mvc.perform(get("/api/perfumes/2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.releaseYear").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.notes.top").isEmpty()).andExpect(jsonPath("$.perfumers").isEmpty())
                .andExpect(jsonPath("$.metrics['identity.rating'].score").value(0.91))
                .andExpect(jsonPath("$.metrics['identity.rating'].nRecords").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.pros").isEmpty()).andExpect(jsonPath("$.cons").isEmpty())
                .andExpect(jsonPath("$.similarFragrances").isEmpty());
    }

    @Test
    void unknownPerfumeReturnsProblemDetail404() throws Exception {
        when(perfumes.detail(999L)).thenThrow(new PerfumeNotFoundException(999L));
        mvc.perform(get("/api/perfumes/999")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404)).andExpect(jsonPath("$.detail").value("Perfume 999 was not found."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void invalidDetailIdReturnsFourHundred(String id) throws Exception {
        mvc.perform(get("/api/perfumes/" + id)).andExpect(status().isBadRequest());
        verifyNoInteractions(perfumes);
    }
}
