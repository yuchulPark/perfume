package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Real JdbcTemplate/transaction manager, fake JDBC transport. No database or external requests. */
class JdbcPerfumeEmbeddingStoreTests {
    @Test
    void sqlNullMetricDetailsRemainMissingWithoutPreventingDocumentGeneration() throws Exception {
        var fixture = new JdbcFixture();
        fixture.metricDetails = null;
        try (var session = fixture.store.open()) {
            var document = session.load(1);
            assertThat(document.metrics().get(0).details()).isNull();
            assertThat(new PerfumeEmbeddingText().render(document)).contains("Metric performance.longevity: score: 10")
                    .doesNotContain("stored details");
            verify(fixture.connection).commit();
        }
        verify(fixture.connection).close();
    }

    @Test
    void validMetricDetailsAreStillParsedAsStoredJson() throws Exception {
        var fixture = new JdbcFixture();
        fixture.metricDetails = "{\"nested\":{\"label\":\"stored\"},\"scores\":[1,2.5]}";
        try (var session = fixture.store.open()) {
            var details = session.load(1).metrics().get(0).details();
            assertThat(details.path("nested").path("label").asText()).isEqualTo("stored");
            assertThat(details.path("scores").get(1).decimalValue()).isEqualByComparingTo("2.5");
        }
    }

    @Test
    void malformedMetricDetailsStillFailAsInvalidSourceAndRollBackTheRead() throws Exception {
        var fixture = new JdbcFixture();
        fixture.metricDetails = "not-json-private-content";
        try (var session = fixture.store.open()) {
            var failure = catchThrowableOfType(EmbeddingFailure.class, () -> session.load(1));
            assertThat(failure.code()).isEqualTo(EmbeddingFailure.Code.INVALID_SOURCE);
            assertThat(failure).hasNoCause().hasMessageNotContaining("private");
            verify(fixture.connection).rollback();
            assertThat(fixture.autoCommit).isTrue();
        }
    }

    @Test
    void readsAllRelationsInOneReadOnlySnapshotAndWritesOnlyBoundAiValues() throws Exception {
        var fixture = new JdbcFixture();
        try (var session = fixture.store.open()) {
            var document = session.load(1);
            assertThat(document.name()).isEqualTo("Stored Perfume");
            assertThat(document.notes()).extracting(PerfumeEmbeddingDocument.Note::layer).containsExactly("TOP", "UNLAYERED");
            assertThat(document.accords().get(0).score()).isEqualByComparingTo("0.12345678901");
            assertThat(document.metrics().get(0).key()).isEqualTo("performance.longevity");
            assertThat(document.metrics().get(0).details().path("custom").asInt()).isEqualTo(42);
            assertThat(document.metrics().get(0).details().path("raw_precision").decimalValue())
                    .isEqualByComparingTo("0.12345678901234567890123456789");
            verify(fixture.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            verify(fixture.connection).setReadOnly(true);
            verify(fixture.connection).commit();
            assertThat(fixture.autoCommit).isTrue();
            var text = new PerfumeEmbeddingText().render(document);
            var hash = new PerfumeEmbeddingText().hash(text);
            assertThat(session.save(1, PerfumeEmbeddingBatchTests.model(), text, hash, PerfumeEmbeddingBatchTests.vector())).isTrue();
            assertThat(fixture.upsertValues).containsEntry(1, 1L).containsEntry(2, "qwen3-embedding:0.6b")
                    .containsEntry(3, PerfumeEmbeddingBatchTests.model().digest()).containsEntry(5, text).containsEntry(6, hash);
            assertThat(fixture.upsertValues.get(7).toString()).startsWith("[1.0,0.0,");
            assertThat(fixture.sql).anyMatch(sql -> sql.contains("SET TRANSACTION READ ONLY"));
            assertThat(fixture.sql.stream().filter(sql -> sql.stripLeading().startsWith("INSERT")).toList())
                    .containsExactly(JdbcPerfumeEmbeddingStore.UPSERT);
        }
        assertThat(fixture.sql).anyMatch(sql -> sql.startsWith("SELECT pg_advisory_unlock"));
        verify(fixture.connection).close();
    }

    @Test
    void failedReadRollsBackAndDoesNotPoisonNextPerfumeOrLeaveTransactionOpen() throws Exception {
        var fixture = new JdbcFixture();
        try (var session = fixture.store.open()) {
            fixture.readFailure = true;
            assertThatThrownBy(() -> session.load(1)).isInstanceOf(EmbeddingFailure.class).hasNoCause().hasMessageNotContaining("private");
            verify(fixture.connection).rollback();
            assertThat(fixture.autoCommit).isTrue();
            fixture.readFailure = false;
            assertThat(session.load(1).id()).isEqualTo(1);
        }
    }

    @Test
    void busyBatchNeverReadsTheCatalogOrUnlocksAnotherSessionsLock() throws Exception {
        var fixture = new JdbcFixture();
        fixture.lockAvailable = false;
        assertThatThrownBy(fixture.store::open).isInstanceOf(EmbeddingFailure.class).hasMessageContaining("BATCH_BUSY");
        assertThat(fixture.sql).containsExactly("SELECT pg_try_advisory_lock(?)");
        verify(fixture.connection).close();
    }

    @Test
    void missingOrIncompatibleManualSchemaFailsBeforeAnyWriteAndReleasesLock() throws Exception {
        var fixture = new JdbcFixture();
        fixture.schemaValid = false;
        assertThatThrownBy(fixture.store::open).isInstanceOf(EmbeddingFailure.class).hasMessageContaining("DATABASE");
        assertThat(fixture.sql).noneMatch(sql -> sql.stripLeading().startsWith("INSERT"));
        assertThat(fixture.sql).anyMatch(sql -> sql.startsWith("SELECT pg_advisory_unlock"));
        verify(fixture.connection).close();
    }

    @Test
    void unlockFailureAbortsPhysicalConnectionInsteadOfReturningALockedSessionToPool() throws Exception {
        var fixture = new JdbcFixture();
        var session = fixture.store.open();
        fixture.unlockFailure = true;
        assertThatThrownBy(session::close).isInstanceOf(EmbeddingFailure.class).hasMessageContaining("DATABASE");
        verify(fixture.connection).abort(any());
        verify(fixture.connection).close();
        session.close(); // Idempotent resource cleanup.
        verify(fixture.connection, times(1)).close();
    }

    @Test
    void conditionalUpsertIsIdempotentAndKeepsOriginalCreationTimestamp() throws Exception {
        var fixture = new JdbcFixture();
        try (var session = fixture.store.open()) {
            fixture.updateCount = 0;
            var text = "stored text";
            assertThat(session.save(1, PerfumeEmbeddingBatchTests.model(), text, new PerfumeEmbeddingText().hash(text),
                    PerfumeEmbeddingBatchTests.vector())).isFalse();
        }
        assertThat(JdbcPerfumeEmbeddingStore.UPSERT).contains("ON CONFLICT", "IS DISTINCT FROM", "updated_at = CURRENT_TIMESTAMP")
                .doesNotContain("DELETE", "created_at =");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalDatabaseFailureClosesTheActualStoreSessionAndPreservesItsEarlierAutocommit(boolean unlockFailure) throws Exception {
        var fixture = new JdbcFixture();
        var session = spy(fixture.store.open());
        var source = mock(PerfumeEmbeddingStore.class);
        when(source.open()).thenReturn(session);
        doReturn(new PerfumeEmbeddingStore.Window(3, 3)).when(session).window(0);
        doReturn(List.of(1L, 2L, 3L)).when(session).nextIds(0, 3, 3);
        doReturn(PerfumeEmbeddingBatchTests.document(1, "Committed")).when(session).load(1);
        doReturn(Optional.empty()).when(session).findHash(1, PerfumeEmbeddingBatchTests.model());
        doThrow(new EmbeddingFailure(EmbeddingFailure.Code.DATABASE)).when(session).load(2);
        fixture.unlockFailure = unlockFailure;
        var client = mock(PerfumeEmbeddingClient.class);
        when(client.embed(any(), anyString())).thenReturn(PerfumeEmbeddingBatchTests.vector());
        var batch = new PerfumeEmbeddingBatch(source, client, new PerfumeEmbeddingText());

        var failure = catchThrowableOfType(EmbeddingFailure.class,
                () -> batch.execute(PerfumeEmbeddingBatchTests.options(10, 3, 0, PerfumeEmbeddingBatchTests.model())));
        assertThat(failure.code()).isEqualTo(EmbeddingFailure.Code.DATABASE);
        assertThat(failure.result().created()).isEqualTo(1);
        assertThat(failure.result().processed()).isEqualTo(2);
        assertThat(failure.result().failed()).isEqualTo(1);
        verify(session, never()).load(3);
        verify(client, times(1)).embed(any(), anyString());
        assertThat(fixture.upsertValues).containsEntry(1, 1L);
        assertThat(fixture.sql.stream().filter(sql -> sql.stripLeading().startsWith("INSERT")).toList())
                .containsExactly(JdbcPerfumeEmbeddingStore.UPSERT);
        assertThat(fixture.sql).anyMatch(sql -> sql.startsWith("SELECT pg_advisory_unlock"));
        verify(fixture.connection, never()).rollback();
        if (unlockFailure) { verify(fixture.connection).abort(any()); }
        else { verify(fixture.connection, never()).abort(any()); }
        verify(fixture.connection).close();
    }

    private static class JdbcFixture {
        final Connection connection = mock(Connection.class);
        final AtomicBoolean autoCommit = new AtomicBoolean(true);
        final List<String> sql = new ArrayList<>();
        final Map<Integer, Object> upsertValues = new HashMap<>();
        final JdbcPerfumeEmbeddingStore store;
        boolean lockAvailable = true, schemaValid = true, readFailure, unlockFailure;
        int updateCount = 1;
        String metricDetails = "{\"custom\":42,\"raw_precision\":0.12345678901234567890123456789}";

        JdbcFixture() throws Exception {
            var datasource = mock(DataSource.class);
            when(datasource.getConnection()).thenReturn(connection);
            when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
            doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; }).when(connection).setAutoCommit(anyBoolean());
            when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            var metadata = mock(DatabaseMetaData.class);
            when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
            when(connection.getMetaData()).thenReturn(metadata);
            when(connection.prepareStatement(anyString())).thenAnswer(call -> {
                String query = call.getArgument(0);
                var parameters = new HashMap<Integer, Object>();
                var statement = mock(PreparedStatement.class, invocation -> {
                    if (invocation.getMethod().getName().startsWith("set") && invocation.getArguments().length >= 2
                            && invocation.getArgument(0) instanceof Integer position) {
                        parameters.put(position, invocation.getArgument(1)); return null;
                    }
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
                });
                when(statement.executeQuery()).thenAnswer(ignored -> rows(query));
                when(statement.executeUpdate()).thenAnswer(ignored -> {
                    assertThat(autoCommit).isTrue();
                    sql.add(query); upsertValues.clear(); upsertValues.putAll(parameters); return updateCount;
                });
                return statement;
            });
            when(connection.createStatement()).thenAnswer(call -> {
                var statement = mock(Statement.class);
                when(statement.executeQuery(anyString())).thenAnswer(ignored -> rows(ignored.getArgument(0)));
                when(statement.executeUpdate(anyString())).thenAnswer(ignored -> { sql.add(ignored.getArgument(0)); return 0; });
                return statement;
            });
            store = new JdbcPerfumeEmbeddingStore(datasource, new ObjectMapper());
        }

        ResultSet rows(String query) throws SQLException {
            sql.add(query);
            if (query.startsWith("SELECT pg_try_advisory_lock")) { return results(List.of(Map.of("value", lockAvailable))); }
            if (query.startsWith("SELECT pg_advisory_unlock")) {
                if (unlockFailure) { throw new SQLException("private-unlock-diagnostic", "08006"); }
                return results(List.of(Map.of("value", true)));
            }
            if (query.contains("SELECT current_database()")) { return results(List.of(Map.of("value", schemaValid))); }
            if (query.contains("WHERE false")) { return results(List.of()); }
            if (query.contains("FROM public.perfumes p")) {
                return results(List.of(Map.of("id", 1L, "name", "Stored Perfume", "brand_name", "Stored Brand", "release_year", 2021)));
            }
            if (query.contains("FROM public.perfume_notes")) {
                return results(List.of(Map.of("layer", "TOP", "name", "Bergamot", "position", 0), Map.of("layer", "UNLAYERED", "name", "Tea")));
            }
            if (query.contains("FROM public.perfume_accords")) {
                return results(List.of(Map.of("name", "citrus", "percentage", 60, "score", new java.math.BigDecimal("0.12345678901"), "position", 0)));
            }
            if (query.contains("FROM public.perfume_metrics")) {
                if (readFailure) { throw new SQLException("private-read-diagnostic", "42000"); }
                var metric = new HashMap<String, Object>();
                metric.put("metric_key", "performance.longevity");
                metric.put("score", java.math.BigDecimal.TEN);
                metric.put("details", metricDetails);
                return results(List.of(metric));
            }
            throw new AssertionError("Unexpected test SQL: " + query);
        }

        private ResultSet results(List<Map<String, Object>> values) throws SQLException {
            var result = mock(ResultSet.class);
            var metadata = mock(ResultSetMetaData.class);
            when(metadata.getColumnCount()).thenReturn(values.isEmpty() ? 0 : values.get(0).size());
            when(result.getMetaData()).thenReturn(metadata);
            var position = new AtomicInteger(-1);
            when(result.next()).thenAnswer(call -> position.incrementAndGet() < values.size());
            for (String method : List.of("getString", "getObject", "getLong", "getInt", "getBoolean", "getBigDecimal")) {
                // JdbcTemplate's scalar mapper reads by numeric index; entity mapper reads by column name.
                var answer = (org.mockito.stubbing.Answer<Object>) call -> {
                    var row = values.get(position.get());
                    Object value = call.getArgument(0) instanceof Integer index ? row.values().toArray()[index - 1] : row.get(call.getArgument(0));
                    return switch (call.getMethod().getName()) {
                        case "getString" -> value == null ? null : value.toString();
                        case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                        case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                        case "getBoolean" -> value != null && (Boolean) value;
                        default -> value;
                    };
                };
                switch (method) {
                    case "getString" -> when(result.getString(anyString())).thenAnswer(answer);
                    case "getObject" -> { when(result.getObject(anyString(), any(Class.class))).thenAnswer(answer); when(result.getObject(anyInt())).thenAnswer(answer); }
                    case "getLong" -> when(result.getLong(anyString())).thenAnswer(answer);
                    case "getInt" -> when(result.getInt(anyString())).thenAnswer(answer);
                    case "getBoolean" -> when(result.getBoolean(anyInt())).thenAnswer(answer);
                    case "getBigDecimal" -> when(result.getBigDecimal(anyString())).thenAnswer(answer);
                    default -> throw new AssertionError();
                }
            }
            return result;
        }
    }
}
