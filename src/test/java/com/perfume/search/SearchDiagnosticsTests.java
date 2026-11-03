package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.perfume.search.SearchDiagnostics.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;

/** All HTTP/JDBC failures are synthetic. No PostgreSQL or Elasticsearch endpoint is contacted. */
class SearchDiagnosticsTests {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String RAW = "diag-api-secret diag-db-password Authorization diag-document-secret diag-url-secret";

    @ParameterizedTest
    @CsvSource({"PUT,/index,INDEX_CREATE", "GET,/_alias/perfumes-search,ALIAS_LOOKUP",
            "POST,/index/_bulk,BULK_INDEX", "POST,/index/_refresh,INDEX_REFRESH",
            "GET,/index/_count,COUNT_VERIFY", "POST,/_aliases,ALIAS_SWAP", "POST,/index/_search,SEARCH"})
    void httpFailuresExposeOnlyStatusOperationAndAllowlistedType(String method, String path, Stage stage) throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(400, mapper.writeValueAsString(Map.of("error", Map.of("type", "illegal_argument_exception", "reason", RAW))));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var logs = new Logs()) {
            var failure = catchThrowable(() -> transport(client).request(method, path + "?token=diag-url-secret", RAW));
            assertThat(failure).isInstanceOf(Failure.class);
            var diagnostic = (Failure) failure;
            assertThat(diagnostic.stage).isEqualTo(stage); assertThat(diagnostic.code).isEqualTo(Code.HTTP_STATUS);
            assertThat(diagnostic.httpStatus).isEqualTo(400); assertThat(diagnostic.esType).isEqualTo("illegal_argument_exception");
            assertThat(logs.text()).contains("stage=" + stage, "http_status=400", "es_type=illegal_argument_exception");
            assertRedacted(logs.text() + stack(failure));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"unrecognised_diag-document-secret", "illegal_argument_exception\nAuthorization diag-api-secret"})
    void arbitraryServerTypesAndBodiesCannotInjectLogs(String type) throws Exception {
        var client = mock(HttpClient.class);
        var response = reply(403, mapper.writeValueAsString(Map.of("error", Map.of("type", type, "reason", RAW))));
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var logs = new Logs()) {
            var failure = catchThrowable(() -> transport(client).request("PUT", "/index?token=diag-url-secret", RAW));
            assertThat(logs.text()).contains("http_status=403", "es_type=OTHER").doesNotContain("unrecognised");
            assertRedacted(logs.text() + stack(failure));
        }
    }

    @Test void missingAliasIsInformationalAndDoesNotBecomeAnIndexingFailure() throws Exception {
        var client = mock(HttpClient.class); var response = reply(404, RAW);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        try (var logs = new Logs()) {
            assertThat(transport(client).request("GET", "/_alias/perfumes-search", null).isEmpty()).isTrue();
            assertThat(logs.text()).contains("stage=ALIAS_LOOKUP outcome=not_found http_status=404");
            assertThat(logs.events.list).noneMatch(e -> e.getLevel().equals(Level.ERROR));
            assertRedacted(logs.text());
        }
    }

    @ParameterizedTest @EnumSource(value = Code.class, names = {"CONNECT_TIMEOUT", "REQUEST_TIMEOUT", "CONNECTION_FAILED", "TLS_FAILED", "HTTP_IO_FAILED", "OPERATION_FAILED"})
    void transportExceptionsUseCodesWithoutRawMessagesOrCauses(Code code) throws Exception {
        Exception error = switch (code) {
            case CONNECT_TIMEOUT -> new java.net.http.HttpConnectTimeoutException(RAW);
            case REQUEST_TIMEOUT -> new java.net.http.HttpTimeoutException(RAW);
            case CONNECTION_FAILED -> new java.net.ConnectException(RAW);
            case TLS_FAILED -> new javax.net.ssl.SSLException(RAW);
            case HTTP_IO_FAILED -> new java.io.IOException(RAW);
            default -> new IllegalArgumentException(RAW);
        };
        error.addSuppressed(new IllegalStateException(RAW));
        var client = mock(HttpClient.class);
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(error);
        try (var logs = new Logs()) {
            var failure = catchThrowable(() -> transport(client).request("PUT", "/index?token=diag-url-secret", RAW));
            assertThat(((Failure) failure).code).isEqualTo(code);
            assertThat(logs.text()).contains("stage=INDEX_CREATE", "code=" + code);
            assertRedacted(logs.text() + stack(failure));
        }
    }

    @ParameterizedTest @CsvSource({"SNAPSHOT_OPEN,08001", "ADVISORY_LOCK,42501", "SOURCE_COUNT,42P01", "SOURCE_READ,22000", "SNAPSHOT_CLOSE,08006"})
    void sqlFailuresExposeThePreciseStageAndSqlState(Stage stage, String state) throws Exception {
        var fixture = new JdbcFixture(); var sql = new SQLException(RAW, state);
        sql.setNextException(new SQLException(RAW)); sql.addSuppressed(new IllegalStateException(RAW));
        switch (stage) {
            case SNAPSHOT_OPEN -> when(fixture.source.getConnection()).thenThrow(sql);
            case ADVISORY_LOCK -> when(fixture.lock.executeQuery()).thenThrow(sql);
            case SOURCE_COUNT -> when(fixture.count.executeQuery()).thenThrow(sql);
            case SOURCE_READ -> when(fixture.read.executeQuery()).thenThrow(sql);
            case SNAPSHOT_CLOSE -> doThrow(sql).when(fixture.connection).rollback();
            default -> throw new AssertionError(stage);
        }
        try (var logs = new Logs()) {
            var failure = catchThrowable(() -> {
                try (var snapshot = new JdbcCatalogSnapshot(fixture.source)) {
                    if (stage == Stage.SOURCE_COUNT) snapshot.count();
                    if (stage == Stage.SOURCE_READ) snapshot.next(0, 200);
                }
            });
            assertThat(((Failure) failure).stage).isEqualTo(stage);
            assertThat(((Failure) failure).sqlState).isEqualTo(state);
            assertThat(logs.text()).contains("stage=" + stage, "code=SQL_FAILED", "sql_state=" + state);
            assertRedacted(logs.text() + stack(failure));
            if (stage != Stage.SNAPSHOT_OPEN) verify(fixture.connection).close();
            verify(fixture.connection, never()).commit();
        }
    }

    @Test void lockContentionIsDifferentFromSqlFailureAndInvalidSqlStateIsRejected() throws Exception {
        var fixture = new JdbcFixture(); when(fixture.lockRow.getBoolean(1)).thenReturn(false);
        try (var logs = new Logs()) {
            var failure = catchThrowable(() -> new JdbcCatalogSnapshot(fixture.source));
            assertThat(((Failure) failure).code).isEqualTo(Code.LOCK_BUSY);
            assertThat(logs.text()).contains("stage=ADVISORY_LOCK", "code=LOCK_BUSY");
            when(fixture.source.getConnection()).thenThrow(new SQLException(RAW, RAW));
            failure = catchThrowable(() -> new JdbcCatalogSnapshot(fixture.source));
            assertThat(((Failure) failure).sqlState).isEqualTo("UNKNOWN");
            assertRedacted(logs.text() + stack(failure));
        }
    }

    @ParameterizedTest @EnumSource(value = Stage.class, names = {"SOURCE_COUNT", "ALIAS_LOOKUP", "INDEX_CREATE", "SOURCE_READ", "BULK_INDEX", "INDEX_REFRESH", "COUNT_VERIFY", "ALIAS_RECHECK", "ALIAS_SWAP", "ALIAS_VERIFY"})
    void reindexerRetainsFailureStageAndProtectsIndices(Stage failedAt) {
        var fixture = new IndexFixture(); fixture.failAt = failedAt;
        try (var logs = new Logs()) {
            var failure = catchThrowable(fixture::execute);
            assertThat(((Failure) failure).stage).isEqualTo(failedAt);
            assertThat(logs.text()).contains("stage=" + failedAt + " outcome=started", "stage=" + failedAt + " code=");
            assertRedacted(logs.text() + stack(failure));
            assertThat(fixture.deletes).isZero();
            if (failedAt != Stage.ALIAS_SWAP && failedAt != Stage.ALIAS_VERIFY) assertThat(fixture.swaps).isZero();
        }
    }

    @Test void bulkItemAndCountFailuresHaveDistinctSafeCodes() {
        var fixture = new IndexFixture(); fixture.bulkError = true;
        try (var logs = new Logs()) {
            var failure = catchThrowable(fixture::execute);
            assertThat(((Failure) failure).code).isEqualTo(Code.BULK_ITEM_FAILED);
            assertThat(logs.text()).contains("item_status=400", "es_type=mapper_parsing_exception");
            assertRedacted(logs.text() + stack(failure));
            assertThat(fixture.swaps).isZero();
        }
        fixture = new IndexFixture(); fixture.wrongCount = true;
        try (var logs = new Logs()) {
            var failure = catchThrowable(fixture::execute);
            assertThat(((Failure) failure).code).isEqualTo(Code.COUNT_MISMATCH);
            assertThat(logs.text()).contains("stage=COUNT_VERIFY sourceCount=1 processed=1 indexedCount=0");
            assertThat(fixture.swaps).isZero();
        }
    }

    @Test void cliRetainsSanitizedSqlCauseEvenWithDebugLoggingAndSensitiveJdbcQuery() throws Exception {
        var driver = mock(java.sql.Driver.class);
        String url = "jdbc:diagnostic-fixture:?password=diag-url-secret";
        when(driver.connect(eq(url), any())).thenThrow(new SQLException(RAW, "08001"));
        DriverManager.registerDriver(driver);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME); Level oldLevel = root.getLevel();
        try (var logs = new Logs(); var ignored = mockConstruction(ElasticHttpTransport.class, (mock, context) -> {
            assertThat(context.arguments()).hasSize(4);
            assertThat(context.arguments().get(2)).isEqualTo(Duration.ofSeconds(30));
            assertThat(context.arguments().get(3)).isEqualTo(Duration.ofSeconds(5));
        })) {
            root.setLevel(Level.DEBUG);
            var env = environment().withProperty("DB_URL", url).withProperty("DB_USERNAME", "fixture-user")
                    .withProperty("DB_PASSWORD", "diag-db-password");
            var failure = catchThrowable(() -> ElasticsearchReindexCli.execute(env));
            assertThat(failure).hasMessageContaining("stage=SNAPSHOT_OPEN code=SQL_FAILED").hasMessageContaining("sql_state=08001")
                    .hasCauseInstanceOf(Failure.class);
            assertRedacted(logs.text() + stack(failure));
            verify(driver).connect(eq(url), any());
        } finally { root.setLevel(oldLevel); DriverManager.deregisterDriver(driver); }
    }

    @Test void configurationFailuresAreDiagnosedBeforeOpeningAnySnapshot() {
        try (var logs = new Logs()) {
            var env = new MockEnvironment().withProperty("perfume.elasticsearch.url", "https://user:diag-api-secret@host/?password=diag-url-secret");
            var failure = catchThrowable(() -> ElasticsearchReindexCli.execute(env));
            assertThat(failure).hasMessageContaining("stage=CONFIGURATION code=OPERATION_FAILED").hasCauseInstanceOf(Failure.class);
            assertThat(logs.text()).doesNotContain("stage=SNAPSHOT_OPEN");
            assertRedacted(logs.text() + stack(failure));
        }
    }

    private MockEnvironment environment() throws Exception {
        Path key = directory.resolve("key"); Files.writeString(key, "diag-api-secret");
        return new MockEnvironment().withProperty("perfume.elasticsearch.url", "https://elasticsearch:9200")
                .withProperty("perfume.elasticsearch.api-key-file", key.toString())
                .withProperty("perfume.elasticsearch.index-api-key-file", key.toString())
                .withProperty("perfume.elasticsearch.ca-file", "unused-test-ca");
    }
    private ElasticHttpTransport transport(HttpClient client) throws Exception {
        return new ElasticHttpTransport(ElasticsearchOptions.from(environment(), false), mapper, Duration.ofMillis(1500), client);
    }
    @SuppressWarnings("unchecked") private HttpResponse<String> reply(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body); return response;
    }
    private void assertRedacted(String text) {
        assertThat(text).doesNotContain("diag-api-secret", "diag-db-password", "Authorization", "diag-document-secret", "diag-url-secret");
    }
    private String stack(Throwable failure) {
        var output = new StringWriter(); failure.printStackTrace(new PrintWriter(output)); return output.toString();
    }
    private static class Logs implements AutoCloseable {
        final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        final ListAppender<ILoggingEvent> events = new ListAppender<>();
        Logs() { events.start(); root.addAppender(events); }
        String text() { return events.list.stream().map(e -> e.getFormattedMessage() + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy())))
                .collect(java.util.stream.Collectors.joining("\n")); }
        public void close() { root.detachAppender(events); events.stop(); }
    }
    private class JdbcFixture {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement lock = mock(PreparedStatement.class), count = mock(PreparedStatement.class), read = mock(PreparedStatement.class);
        final ResultSet lockRow = mock(ResultSet.class);
        JdbcFixture() throws Exception {
            when(source.getConnection()).thenReturn(connection);
            when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(lock);
            when(connection.prepareStatement("SELECT count(*) FROM public.perfumes")).thenReturn(count);
            when(connection.prepareStatement(JdbcCatalogSnapshot.DOCUMENTS)).thenReturn(read);
            when(lock.executeQuery()).thenReturn(lockRow); when(lockRow.next()).thenReturn(true); when(lockRow.getBoolean(1)).thenReturn(true);
        }
    }
    private class IndexFixture {
        Stage failAt; boolean bulkError, wrongCount; int aliasReads, swaps, deletes;
        JsonNode alias = mapper.createObjectNode();
        final CatalogSnapshot snapshot = mock(CatalogSnapshot.class);
        IndexFixture() {
            when(snapshot.count()).thenAnswer(invocation -> { fail(Stage.SOURCE_COUNT); return 1L; });
            when(snapshot.next(0, 200)).thenAnswer(invocation -> {
                fail(Stage.SOURCE_READ); return List.of(new PerfumeSearchDocument(1, "diag-document-secret", "Brand", "brand", RAW, null,
                        List.of(), List.of(), List.of(), List.of(), List.of()));
            });
            when(snapshot.next(1, 200)).thenReturn(List.of());
        }
        void fail(Stage stage) { if (failAt == stage) throw new IllegalStateException(RAW); }
        ElasticsearchReindexer.Result execute() {
            return new ElasticsearchReindexer(this::request, mapper, "perfumes-search", Clock.systemUTC()).execute(snapshot);
        }
        JsonNode request(String method, String path, String body) {
            if (method.equals("DELETE")) deletes++;
            if (path.equals("/_alias/perfumes-search")) {
                fail(switch (++aliasReads) { case 1 -> Stage.ALIAS_LOOKUP; case 2 -> Stage.ALIAS_RECHECK; default -> Stage.ALIAS_VERIFY; });
                return alias;
            }
            if (method.equals("PUT")) { fail(Stage.INDEX_CREATE); return mapper.valueToTree(Map.of("acknowledged", true)); }
            if (path.endsWith("/_bulk")) {
                fail(Stage.BULK_INDEX);
                var created = new java.util.LinkedHashMap<String, Object>(); created.put("_id", "1"); created.put("status", bulkError ? 400 : 201);
                if (bulkError) created.put("error", Map.of("type", "mapper_parsing_exception", "reason", RAW));
                return mapper.valueToTree(Map.of("errors", bulkError, "items", List.of(Map.of("create", created))));
            }
            if (path.endsWith("/_refresh")) { fail(Stage.INDEX_REFRESH); return mapper.valueToTree(Map.of("_shards", Map.of("failed", 0))); }
            if (path.endsWith("/_count")) { fail(Stage.COUNT_VERIFY); return mapper.valueToTree(Map.of("count", wrongCount ? 0 : 1, "_shards", Map.of("failed", 0))); }
            if (path.equals("/_aliases")) {
                swaps++; fail(Stage.ALIAS_SWAP);
                try { alias = mapper.valueToTree(Map.of(mapper.readTree(body).at("/actions/0/add/index").asText(), Map.of("aliases", Map.of("perfumes-search", Map.of())))); }
                catch (Exception invalid) { throw new AssertionError(invalid); }
                return mapper.valueToTree(Map.of("acknowledged", true));
            }
            throw new AssertionError(method + " " + path);
        }
    }
}
