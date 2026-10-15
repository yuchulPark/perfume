package com.perfume.scentrev.preview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse;
import com.perfume.scentrev.dto.ScentRevFilteredSearchResponse.Fragrance;
import com.perfume.scentrev.preview.ScentRevBrandPreviewResult.StopReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Only mocked list calls; no credentials, database, Import, profiles or live ScentRev requests. */
class ScentRevBrandPreviewServiceTests {
    private final ScentRevMcpClient client = mock(ScentRevMcpClient.class);
    private final ScentRevBrandPreviewLocalBrand local = mock(ScentRevBrandPreviewLocalBrand.class);
    private final ScentRevBrandPreviewService service = new ScentRevBrandPreviewService(local, client);

    ScentRevBrandPreviewServiceTests() {
        when(local.read("creed")).thenReturn(new ScentRevBrandPreviewLocalBrand.Snapshot("Creed", "creed", 12));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 7, 44})
    void completesOnlyOnTheFinalPageAndUsesPageCountsRatherThanTotalReturned(int total) {
        catalog(total);
        var result = service.preview(options(5, 5));
        int pages = Math.max(1, (total + 9) / 10);
        assertThat(result.completed()).isTrue();
        assertThat(result.hasMore()).isFalse();
        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(result.rawCandidateCount()).isEqualTo(total);
        assertThat(result.candidateCount()).isEqualTo(total);
        assertThat(result.listApiCalls()).isEqualTo(pages);
        assertThat(result.pagesFetched()).isEqualTo(pages);
        assertThat(result.dbPerfumeCount()).isEqualTo(12);
        assertThat(result.estimatedFullImportCalls()).isEqualTo((long) total + pages);
        if (total == 44) { assertThat(result.referenceCallsAssumingAllObservedCandidatesNew()).isEqualTo(53); }
        verifyListOnly(pages);
    }

    @Test
    void sixtyCandidatesStopAtFivePagesWithoutCallingTheSixthOrClaimingAFullCount() {
        catalog(60);
        var result = service.preview(options(5, 5));
        assertThat(result.candidateCount()).isEqualTo(50);
        assertThat(result.completed()).isFalse();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.stopReason()).isEqualTo(StopReason.MAX_PAGES);
        assertThat(result.nextOffset()).isEqualTo(50);
        assertThat(result.estimatedFullImportCalls()).isNull();
        assertThat(result.basicImportCallsForObservedCandidates()).isEqualTo(55);
        verifyListOnly(5);
    }

    @Test
    void explicitPageIncreaseStartsAgainAtZeroAndConsumesAnotherSixCalls() {
        catalog(60);
        assertThat(service.preview(options(5, 5)).completed()).isFalse();
        var result = service.preview(options(6, 6));
        assertThat(result.completed()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(60);
        assertThat(result.listApiCalls()).isEqualTo(6);
        assertThat(result.estimatedFullImportCalls()).isEqualTo(66L);
        assertThat(result.referenceCallsAssumingAllObservedCandidatesNew()).isEqualTo(71);
        verify(client, times(2)).searchFragrancesFiltered("creed", 0);
        verify(client, times(11)).searchFragrancesFiltered(eq("creed"), anyInt());
        verifyNoMoreInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    void explicitCallBudgetIsCheckedBeforeEveryAttempt(int budget) {
        catalog(60);
        var result = service.preview(options(5, budget));
        assertThat(result.stopReason()).isEqualTo(StopReason.API_BUDGET);
        assertThat(result.listApiCalls()).isEqualTo(budget);
        assertThat(result.pagesFetched()).isEqualTo(budget);
        assertThat(result.candidateCount()).isEqualTo(budget * 10);
        assertThat(result.completed()).isFalse();
        if (budget == 0) { assertThat(result.hasMore()).isNull(); }
        verifyListOnly(budget);
    }

    @Test
    void neverExceedsOneHundredCallsEvenWhenProviderAlwaysReportsMore() {
        when(client.searchFragrancesFiltered(eq("creed"), anyInt()))
                .thenAnswer(call -> page(call.getArgument(1), true, row("item-" + call.getArgument(1), null, null)));
        var result = service.preview(options(100, 100));
        assertThat(result.listApiCalls()).isEqualTo(100);
        assertThat(result.candidateCount()).isEqualTo(100);
        assertThat(result.completed()).isFalse();
        verifyListOnly(100);
    }

    @Test
    void deduplicatesSlugsPublicIdsAndLateIdentityLinksWithinAndAcrossPages() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true,
                row("alpha", null, "creed"), row("beta", "same-id", "creed"), row("beta", "same-id", "creed")));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false,
                row("alpha", "same-id", "creed"), row("gamma", "same-id", "creed"), row("delta", "other-id", "creed")));
        var result = service.preview(options(5, 5));
        assertThat(result.rawCandidateCount()).isEqualTo(6);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.duplicateCount()).isEqualTo(4);
        assertThat(result.completed()).isTrue();
        verifyListOnly(2);
    }

    @Test
    void mergingOldCandidatesDoesNotHideANewIdentityOnATruncatedPage() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true,
                row("alpha", null, null), row("beta", "same", null)));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, true,
                row("alpha", "same", null), row("gamma", "new", null)));
        when(client.searchFragrancesFiltered("creed", 20)).thenReturn(page(20, false, row("delta", "last", null)));
        var result = service.preview(options(5, 5));
        assertThat(result.completed()).isTrue();
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.rawCandidateCount()).isEqualTo(5);
        assertThat(result.duplicateCount()).isEqualTo(2);
        verifyListOnly(3);
    }

    @Test
    void foreignAndMissingBrandMetadataRemainUnverifiedCandidatesWithoutProfileCalls() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, false,
                row("alpha", "a", "creed"), row("foreign", "b", "another"), row("unknown", "c", null)));
        var result = service.preview(options(5, 5));
        assertThat(result.candidateCount()).isEqualTo(3);
        assertThat(result.foreignBrandCandidateRows()).isEqualTo(1);
        assertThat(result.completed()).isTrue();
        verifyListOnly(1);
    }

    @Test
    void aShortTruncatedPageStillAdvancesByOffsetPlusLimitNotRowCount() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, row("alpha", null, null)));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, row("beta", null, null)));
        assertThat(service.preview(options(5, 5)).candidateCount()).isEqualTo(2);
        verifyListOnly(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsRepeatedPagesEvenWhenReorderedAndMarkedFinal(boolean truncated) {
        var first = row("alpha", "a", "creed"); var second = row("beta", "b", "creed");
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, first, second));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, truncated, second, first));
        var result = service.preview(options(5, 5));
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_PAGE);
        assertThat(result.completed()).isFalse();
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.listApiCalls()).isEqualTo(2);
        verifyListOnly(2);
    }

    @Test
    void renamedSlugsCannotHideAnIdenticalPublicIdPage() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, row("alpha", "same", null)));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, false, row("renamed", "same", null)));
        assertThat(service.preview(options(5, 5)).stopReason()).isEqualTo(StopReason.INVALID_PAGE);
        verifyListOnly(2);
    }

    @Test
    void aTruncatedSubsetWithNoNewIdentityStopsWithoutAcceptingIt() {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, row("alpha", "a", null), row("beta", "b", null)));
        when(client.searchFragrancesFiltered("creed", 10)).thenReturn(page(10, true, row("alpha", "a", null)));
        var result = service.preview(options(5, 5));
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_PAGE);
        assertThat(result.rawCandidateCount()).isEqualTo(2);
        verifyListOnly(2);
    }

    @ParameterizedTest
    @MethodSource("invalidPages")
    void rejectsMalformedPagesWithoutInventingAnEmptyCompleteCatalog(ScentRevFilteredSearchResponse response) {
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(response);
        var result = service.preview(options(5, 5));
        assertThat(result.stopReason()).isEqualTo(StopReason.INVALID_PAGE);
        assertThat(result.completed()).isFalse();
        assertThat(result.hasMore()).isNull();
        assertThat(result.listApiCalls()).isEqualTo(1);
        assertThat(result.pagesFetched()).isZero();
        assertThat(result.estimatedFullImportCalls()).isNull();
        verifyListOnly(1);
    }

    static Stream<Arguments> invalidPages() {
        return Stream.of(null,
                new ScentRevFilteredSearchResponse(null, false, 0, 10, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), null, 0, 10, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, null, 10, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, null, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, 10, 10, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, -1, 10, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 9, 0, false),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 10, 0, true),
                new ScentRevFilteredSearchResponse(List.of(), false, 0, 10, 1, false),
                page(0, true),
                page(0, false, IntStream.range(0, 11).mapToObj(i -> row("item-" + i, null, null)).toArray(Fragrance[]::new)),
                page(0, false, (Fragrance) null), page(0, false, row(" Bad ", "a", null)),
                page(0, false, row("alpha", " ", null)), page(0, false, row("alpha", "a".repeat(129), null)),
                page(0, false, row("alpha", "a", "Bad Brand")),
                page(0, false, row("alpha", "a", null), row("alpha", "conflicting", null)))
                .map(value -> Arguments.of((Object) value));
    }

    @ParameterizedTest
    @EnumSource(FailureType.class)
    void failedAttemptsConsumeBudgetAndStopWithoutRetryOrThrowingProviderPayloads(FailureType type) {
        var error = mock(ScentRevMcpClientException.class);
        when(error.getFailureType()).thenReturn(type);
        when(error.getRetryAfterMs()).thenReturn(type == FailureType.RATE_LIMIT ? 1234L : null);
        when(error.getMessage()).thenReturn("private-key-and-response");
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page(0, true, row("alpha", "a", null)));
        when(client.searchFragrancesFiltered("creed", 10)).thenThrow(error);
        var result = service.preview(options(5, 5));
        assertThat(result.stopReason()).isEqualTo(StopReason.API_ERROR);
        assertThat(result.failureType()).isEqualTo(type);
        assertThat(result.retryAfterMs()).isEqualTo(type == FailureType.RATE_LIMIT ? 1234L : null);
        assertThat(result.listApiCalls()).isEqualTo(2);
        assertThat(result.pagesFetched()).isEqualTo(1);
        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.completed()).isFalse();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.toString()).doesNotContain("private-");
        verifyListOnly(2);
    }

    @Test
    void unknownFirstCallErrorIsNotACompletedZeroCount() {
        when(client.searchFragrancesFiltered("creed", 0)).thenThrow(new IllegalStateException("private-api-key"));
        var result = service.preview(options(5, 5));
        assertThat(result.stopReason()).isEqualTo(StopReason.API_ERROR);
        assertThat(result.listApiCalls()).isEqualTo(1);
        assertThat(result.completed()).isFalse();
        assertThat(result.hasMore()).isNull();
        verifyListOnly(1);
    }

    @Test
    void invalidLocalBrandStopsBeforeAnyProviderCall() {
        when(local.read("creed")).thenThrow(new IllegalArgumentException("Unverified brand"));
        assertThatThrownBy(() -> service.preview(options(5, 5))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void interruptStopsWithoutCallsAndPreservesTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            var result = service.preview(options(5, 5));
            assertThat(result.stopReason()).isEqualTo(StopReason.INTERRUPTED);
            assertThat(result.listApiCalls()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(client);
        } finally { Thread.interrupted(); }
    }

    @Test
    void quotaMetadataIsNotAVerifiedRemainingBalance() throws Exception {
        var mapper = new ObjectMapper();
        var page = mapper.readValue("""
                {"results":[],"truncated":false,"offset":0,"limit":10,"total_returned":0,
                 "quota":{"remaining":999},"retry_after_ms":1234}
                """, ScentRevFilteredSearchResponse.class);
        when(client.searchFragrancesFiltered("creed", 0)).thenReturn(page);
        var result = service.preview(options(5, 5));
        assertThat(result.completed()).isTrue();
        assertThat(result.retryAfterMs()).isNull();
        verifyListOnly(1);
    }

    private void catalog(int total) {
        when(client.searchFragrancesFiltered(eq("creed"), anyInt())).thenAnswer(call -> {
            int offset = call.getArgument(1);
            var rows = IntStream.range(offset, Math.min(offset + 10, total))
                    .mapToObj(i -> row("item-" + i, "id-" + i, "creed")).toArray(Fragrance[]::new);
            return page(offset, offset + rows.length < total, rows);
        });
    }
    private void verifyListOnly(int pages) {
        var order = inOrder(client);
        for (int i = 0; i < pages; i++) { order.verify(client).searchFragrancesFiltered("creed", i * 10); }
        verifyNoMoreInteractions(client);
        verify(local).read("creed"); verifyNoMoreInteractions(local);
    }
    static ScentRevBrandPreviewOptions options(int pages, int budget) { return new ScentRevBrandPreviewOptions("creed", pages, budget); }
    static Fragrance row(String slug, String id, String brand) { return new Fragrance(slug, id, slug, brand); }
    static ScentRevFilteredSearchResponse page(int offset, boolean more, Fragrance... rows) {
        return new ScentRevFilteredSearchResponse(Arrays.asList(rows), more, offset, 10, rows.length, false);
    }
}
