package com.perfume.ai.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

/** Real JDBC/transaction code with fake driver rows. SQL execution/pgvector plans require a separate approved server test. */
class JdbcPerfumeSemanticSearchRepositoryTests {
    @Test void bindsVectorIdentityAndLimitAndKeepsOnlyTheFiveJoinedMatchingRowsInDatabaseOrder() throws Exception {
        var fixture = new Fixture();
        var result = fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 10);
        assertThat(result).extracting(PerfumeSemanticSearchResponse.Hit::perfumeId).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(result).extracting(PerfumeSemanticSearchResponse.Hit::similarity).containsExactly(0.9, 0.9, 0.5, 0.0, -0.5);
        assertThat(fixture.parameters).containsEntry(2, "qwen3-embedding:0.6b").containsEntry(3, "sha256:" + "a".repeat(64))
                .containsEntry(4, "perfume-v1").containsEntry(5, 10);
        assertThat(fixture.parameters.get(1).toString()).startsWith("[1.0,0.0,").endsWith("]");
        assertThat(fixture.sql).containsExactly("SET TRANSACTION READ ONLY", JdbcPerfumeSemanticSearchRepository.SEARCH_SQL);
        assertThat(JdbcPerfumeSemanticSearchRepository.SEARCH_SQL).contains("OPERATOR(public.<=>) CAST(? AS public.vector(1024))",
                "e.model_name = ? AND e.model_digest = ? AND e.text_version = ?", "JOIN public.perfumes", "JOIN public.brands",
                "ORDER BY cosine_distance ASC, perfume_id ASC", "LIMIT ?", "1 - cosine_distance AS similarity")
                .doesNotContain("source_text", "source_sha256", "SELECT *", "1.0,0.0");
        verify(fixture.statement).setQueryTimeout(5); verify(fixture.statement, never()).executeUpdate();
        verify(fixture.connection).commit(); verify(fixture.connection, never()).rollback();
        verify(fixture.result).close(); verify(fixture.statement).close(); verify(fixture.connection).close();
        assertThat(fixture.autoCommit).isTrue(); assertThat(fixture.readOnly).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"model", "digest", "version"})
    void anotherModelDigestOrTextVersionCannotSupplyFallbackResults(String mismatch) throws Exception {
        var fixture = new Fixture(); fixture.data.clear();
        var model = OllamaQueryEmbeddingAdapterTests.MODEL;
        fixture.data.add(new Row(1, mismatch.equals("model") ? "other-model" : model.name(), mismatch.equals("digest") ? "sha256:" + "b".repeat(64) : model.digest(),
                mismatch.equals("version") ? "perfume-v0" : model.textVersion(), 0, true));
        assertThat(fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), model, 5)).isEmpty();
        assertThat(fixture.sql).hasSize(2); verify(fixture.connection).commit();
    }

    @Test void emptyEmbeddingsAreANormalReadOnlyResult() throws Exception {
        var fixture = new Fixture(); fixture.data.clear();
        assertThat(fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 5)).isEmpty();
        verify(fixture.connection).commit(); verify(fixture.connection).close();
    }

    @ParameterizedTest @CsvSource({"08006,DATABASE_UNAVAILABLE", "57014,DATABASE_TIMEOUT", "42883,PGVECTOR_SEARCH_ERROR", "42P01,PGVECTOR_SEARCH_ERROR"})
    void queryFailuresAreSanitizedAndRolledBackRatherThanReturningAnEmptyList(String state, SemanticSearchFailure.Code expected) throws Exception {
        var fixture = new Fixture(); fixture.queryFailure = new SQLException("private-password-query-vector-sql", state);
        var failure = catchThrowableOfType(SemanticSearchFailure.class,
                () -> fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 5));
        assertThat(failure.code()).isEqualTo(expected); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
        verify(fixture.connection).rollback(); verify(fixture.statement).close(); verify(fixture.connection).close();
    }

    @Test void connectionFailureIsDistinctAndKeepsNoDatabaseCause() throws Exception {
        var source = mock(DataSource.class); when(source.getConnection()).thenThrow(new SQLException("private-password", "08001"));
        var repository = new JdbcPerfumeSemanticSearchRepository(source, 5);
        var failure = catchThrowableOfType(SemanticSearchFailure.class, () -> repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 5));
        assertThat(failure.code()).isEqualTo(SemanticSearchFailure.Code.DATABASE_UNAVAILABLE); assertThat(failure).hasNoCause();
    }

    @Test void aConnectionLostAtCommitIsNotReportedAsAnEmptySuccess() throws Exception {
        var fixture = new Fixture(); doThrow(new SQLException("private-commit-diagnostic", "08006")).when(fixture.connection).commit();
        var failure = catchThrowableOfType(SemanticSearchFailure.class,
                () -> fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 5));
        assertThat(failure.code()).isEqualTo(SemanticSearchFailure.Code.DATABASE_UNAVAILABLE); verify(fixture.connection).close();
    }

    @ParameterizedTest @ValueSource(ints = {0, 21})
    void invalidTopKCannotReachTheDatasource(int topK) throws Exception {
        var fixture = new Fixture();
        assertThatThrownBy(() -> fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, topK))
                .isInstanceOf(SemanticSearchFailure.class).hasMessageContaining("topK");
        verifyNoInteractions(fixture.source);
    }

    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, 1.1, -1.1})
    void nonfiniteOrImpossibleDatabaseScoresAreNotReturned(double score) throws Exception {
        var fixture = new Fixture(); fixture.overrideScore = score;
        assertThat(catchThrowableOfType(SemanticSearchFailure.class,
                () -> fixture.repository.search(OllamaQueryEmbeddingAdapterTests.vector(), OllamaQueryEmbeddingAdapterTests.MODEL, 5)).code())
                .isEqualTo(SemanticSearchFailure.Code.PGVECTOR_SEARCH_ERROR);
        verify(fixture.connection).rollback(); verify(fixture.result).close();
    }

    record Row(long id, String model, String digest, String version, double distance, boolean joined) { }
    private static class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final AtomicBoolean autoCommit = new AtomicBoolean(true), readOnly = new AtomicBoolean(false);
        final Map<Integer, Object> parameters = new HashMap<>();
        final List<String> sql = new ArrayList<>();
        final List<Row> data = new ArrayList<>();
        final JdbcPerfumeSemanticSearchRepository repository;
        final PreparedStatement statement;
        ResultSet result;
        SQLException queryFailure;
        Double overrideScore;
        Fixture() throws Exception {
            var model = OllamaQueryEmbeddingAdapterTests.MODEL;
            data.add(new Row(2, model.name(), model.digest(), model.textVersion(), 0.1, true));
            data.add(new Row(1, model.name(), model.digest(), model.textVersion(), 0.1, true));
            data.add(new Row(3, model.name(), model.digest(), model.textVersion(), 0.5, true));
            data.add(new Row(4, model.name(), model.digest(), model.textVersion(), 1.0, true));
            data.add(new Row(5, model.name(), model.digest(), model.textVersion(), 1.5, true));
            data.add(new Row(6, "other", model.digest(), model.textVersion(), 0, true));
            data.add(new Row(7, model.name(), "sha256:" + "b".repeat(64), model.textVersion(), 0, true));
            data.add(new Row(8, model.name(), model.digest(), "perfume-v0", 0, true));
            data.add(new Row(9, model.name(), model.digest(), model.textVersion(), 0, false));
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
            when(connection.isReadOnly()).thenAnswer(call -> readOnly.get());
            doAnswer(call -> { readOnly.set(call.getArgument(0)); return null; }).when(connection).setReadOnly(anyBoolean());
            when(connection.createStatement()).thenAnswer(call -> {
                var control = mock(Statement.class);
                when(control.executeUpdate(anyString())).thenAnswer(command -> { sql.add(command.getArgument(0)); return 0; }); return control;
            });
            statement = mock(PreparedStatement.class, call -> {
                if (call.getMethod().getName().startsWith("set") && call.getArguments().length == 2) { parameters.put(call.getArgument(0), call.getArgument(1)); return null; }
                return Answers.RETURNS_DEFAULTS.answer(call);
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> { sql.add(call.getArgument(0)); return statement; });
            when(statement.executeQuery()).thenAnswer(call -> {
                assertThat(autoCommit).isFalse(); assertThat(readOnly).isTrue();
                if (queryFailure != null) { throw queryFailure; }
                var matched = data.stream().filter(row -> row.joined && row.model.equals(parameters.get(2)) && row.digest.equals(parameters.get(3)) && row.version.equals(parameters.get(4)))
                        .sorted(Comparator.comparingDouble(Row::distance).thenComparingLong(Row::id)).limit((Integer) parameters.get(5)).toList();
                result = mock(ResultSet.class); var index = new AtomicInteger(-1);
                when(result.next()).thenAnswer(next -> index.incrementAndGet() < matched.size());
                when(result.getLong("perfume_id")).thenAnswer(column -> matched.get(index.get()).id);
                when(result.getString("name")).thenAnswer(column -> "Stored " + matched.get(index.get()).id);
                when(result.getString("brand_name")).thenReturn("Stored brand");
                when(result.getDouble("similarity")).thenAnswer(column -> overrideScore == null ? 1 - matched.get(index.get()).distance : overrideScore);
                return result;
            });
            repository = new JdbcPerfumeSemanticSearchRepository(source, 5);
        }
    }
}
