package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import com.perfume.ai.embedding.PerfumeEmbeddingText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;

class JdbcRecommendationEvidenceRepositoryTests {
    @Test void readsOnlyBoundSearchIdsWithExactModelAndPreservesSearchOrderAndSourceNumbers() throws Exception {
        var fixture = new Fixture(1, 3);
        var result = fixture.repository.find(List.of(hit(3), hit(1)));
        assertThat(result).extracting(row -> row.source().perfumeId()).containsExactly(3L, 1L);
        assertThat(result).extracting(RecommendationEvidence::sourceNumber).containsExactly(1, 2);
        assertThat(fixture.binds).containsEntry(1, EMBEDDING.name()).containsEntry(2, EMBEDDING.digest()).containsEntry(3, "perfume-v1").containsEntry(4, 3L).containsEntry(5, 1L);
        assertThat(fixture.sql).containsExactly("SET TRANSACTION READ ONLY", JdbcRecommendationEvidenceRepository.SQL.formatted("?,?"));
        assertThat(fixture.sql.get(1)).contains("model_name = ? AND e.model_digest = ? AND e.text_version = ?", "e.perfume_id IN (?,?)", "left(e.source_text, 65537)")
                .doesNotContain("e.embedding", "INSERT", "UPDATE", "DELETE");
        verify(fixture.statement).setQueryTimeout(5); verify(fixture.statement, never()).executeUpdate();
        verify(fixture.connection).commit(); verify(fixture.connection, never()).rollback();
        verify(fixture.result).close(); verify(fixture.statement).close(); verify(fixture.connection).close();
        assertThat(fixture.autoCommit).isTrue(); assertThat(fixture.readOnly).isFalse();
        assertThat(result.get(0).toString()).doesNotContain("Bergamot", "sourceText");
    }
    @ParameterizedTest @ValueSource(strings = {"model_name", "model_digest", "text_version", "name", "brand_name", "source_sha256", "perfume_id"})
    void identityOrHashMismatchCannotSupplyEvidence(String field) throws Exception {
        var fixture = new Fixture(1); fixture.rows.get(0).put(field, field.equals("perfume_id") ? 999L : "mismatch");
        assertCode(fixture, RecommendationFailure.Code.EVIDENCE_UNAVAILABLE);
        verify(fixture.connection).rollback(); verify(fixture.connection).close();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "empty", "oversized", "partial", "timestamp", "duplicate", "missing"})
    void missingOrUnboundedSnapshotsNeverBecomeNormalEmptyRecommendations(String kind) throws Exception {
        var fixture = new Fixture(1); var row = fixture.rows.get(0);
        switch (kind) {
            case "null" -> row.put("source_text", null); case "empty" -> row.put("source_text", " ");
            case "oversized" -> row.put("source_length", 65537); case "partial" -> row.put("source_length", 10000);
            case "timestamp" -> row.put("updated_at", null); case "duplicate" -> fixture.rows.add(new HashMap<>(row)); case "missing" -> fixture.rows.clear();
        }
        assertCode(fixture, RecommendationFailure.Code.EVIDENCE_UNAVAILABLE); verify(fixture.connection).rollback();
    }
    @ParameterizedTest @CsvSource({"08006,DATABASE_UNAVAILABLE", "57014,DATABASE_TIMEOUT", "42P01,EVIDENCE_UNAVAILABLE"})
    void sqlErrorsStaySanitizedAndAlwaysCloseTheConnection(String state, RecommendationFailure.Code expected) throws Exception {
        var fixture = new Fixture(1); fixture.failure = new SQLException("private-sql-password-vector", state);
        assertCode(fixture, expected); verify(fixture.connection).rollback(); verify(fixture.statement).close(); verify(fixture.connection).close();
    }
    @Test void connectionAndCommitFailuresAreNotEmptySuccesses() throws Exception {
        var source = mock(DataSource.class); when(source.getConnection()).thenThrow(new SQLException("private-password", "08001"));
        assertThat(catchThrowableOfType(RecommendationFailure.class, () -> new JdbcRecommendationEvidenceRepository(source, EMBEDDING, 5).find(List.of(hit(1)))).code())
                .isEqualTo(RecommendationFailure.Code.DATABASE_UNAVAILABLE);
        var fixture = new Fixture(1); doThrow(new SQLException("private-commit", "08006")).when(fixture.connection).commit();
        assertCode(fixture, RecommendationFailure.Code.DATABASE_UNAVAILABLE); verify(fixture.connection).close();
    }
    @Test void emptyOrDuplicateRequestsDoNotOpenConnections() throws Exception {
        var fixture = new Fixture(1);
        assertThat(fixture.repository.find(List.of())).isEmpty();
        assertThatThrownBy(() -> fixture.repository.find(List.of(hit(1), hit(1)))).isInstanceOf(RecommendationFailure.class);
        verifyNoInteractions(fixture.source);
    }
    private static void assertCode(Fixture fixture, RecommendationFailure.Code expected) {
        var failure = catchThrowableOfType(RecommendationFailure.class, () -> fixture.repository.find(List.of(hit(1))));
        assertThat(failure.code()).isEqualTo(expected); assertThat(failure).hasNoCause().hasMessageNotContaining("private-");
    }
    static class Fixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement;
        final AtomicBoolean autoCommit = new AtomicBoolean(true), readOnly = new AtomicBoolean(false);
        final List<Map<String, Object>> rows = new ArrayList<>();
        final Map<Integer, Object> binds = new HashMap<>();
        final List<String> sql = new ArrayList<>(), lifecycle = new ArrayList<>();
        final JdbcRecommendationEvidenceRepository repository;
        ResultSet result;
        SQLException failure;
        Fixture(long... ids) throws Exception {
            for (long id : ids) {
                var text = text(id); var row = new HashMap<String, Object>();
                row.put("perfume_id", id); row.put("model_name", EMBEDDING.name()); row.put("model_digest", EMBEDDING.digest()); row.put("text_version", EMBEDDING.textVersion());
                row.put("source_text", text); row.put("source_length", text.codePointCount(0, text.length())); row.put("source_sha256", new PerfumeEmbeddingText().hash(text));
                row.put("updated_at", Timestamp.from(evidence(id).snapshotAt())); row.put("name", hit(id).name()); row.put("brand_name", hit(id).brandName()); rows.add(row);
            }
            when(source.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get()); when(connection.isReadOnly()).thenAnswer(call -> readOnly.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
            doAnswer(call -> { readOnly.set(call.getArgument(0)); return null; }).when(connection).setReadOnly(anyBoolean());
            doAnswer(call -> { lifecycle.add("commit"); return null; }).when(connection).commit();
            doAnswer(call -> { lifecycle.add("close"); return null; }).when(connection).close();
            when(connection.createStatement()).thenAnswer(call -> {
                var control = mock(Statement.class); when(control.executeUpdate(anyString())).thenAnswer(command -> { sql.add(command.getArgument(0)); return 0; }); return control;
            });
            statement = mock(PreparedStatement.class, call -> {
                if (call.getMethod().getName().startsWith("set") && call.getArguments().length == 2) { binds.put(call.getArgument(0), call.getArgument(1)); return null; }
                return Answers.RETURNS_DEFAULTS.answer(call);
            });
            when(connection.prepareStatement(anyString())).thenAnswer(call -> { sql.add(call.getArgument(0)); return statement; });
            when(statement.executeQuery()).thenAnswer(call -> {
                assertThat(autoCommit).isFalse(); assertThat(readOnly).isTrue(); lifecycle.add("query");
                if (failure != null) { throw failure; }
                var index = new AtomicInteger(-1);
                result = mock(ResultSet.class, column -> {
                    var method = column.getMethod().getName();
                    if (method.equals("next")) { return index.incrementAndGet() < rows.size(); }
                    if (method.startsWith("get") && column.getArguments().length == 1) { return rows.get(index.get()).get(column.getArgument(0)); }
                    return Answers.RETURNS_DEFAULTS.answer(column);
                }); return result;
            });
            repository = new JdbcRecommendationEvidenceRepository(source, EMBEDDING, 5);
        }
    }
}
