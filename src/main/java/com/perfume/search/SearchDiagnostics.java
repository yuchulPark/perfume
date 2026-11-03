package com.perfume.search;

import java.util.Set;
import org.slf4j.Logger;

/** Only fixed codes, validated SQLState, HTTP status and allowlisted ES types may cross this boundary. */
final class SearchDiagnostics {
    enum Stage {
        CONFIGURATION, SNAPSHOT_OPEN, ADVISORY_LOCK, SOURCE_COUNT, SOURCE_READ, SNAPSHOT_CLOSE,
        ALIAS_LOOKUP, INDEX_CREATE, BULK_INDEX, INDEX_REFRESH, COUNT_VERIFY,
        ALIAS_RECHECK, ALIAS_SWAP, ALIAS_VERIFY, SEARCH, HTTP_REQUEST
    }

    enum Code {
        OPERATION_FAILED, SQL_FAILED, LOCK_BUSY, HTTP_STATUS, CONNECT_TIMEOUT, REQUEST_TIMEOUT, CONNECTION_FAILED,
        TLS_FAILED, REQUEST_INTERRUPTED, HTTP_IO_FAILED, INVALID_RESPONSE, EMPTY_SOURCE, TIME_LIMIT,
        INVALID_SOURCE_ORDER, BULK_FAILED, BULK_ITEM_FAILED, COUNT_MISMATCH, REFRESH_FAILED,
        ALIAS_CHANGED, INVALID_ALIAS, NOT_ACKNOWLEDGED, MAPPING_FAILED, ENCODING_FAILED
    }

    private static final Set<String> ES_TYPES = Set.of("illegal_argument_exception", "security_exception",
            "resource_already_exists_exception", "index_not_found_exception", "mapper_parsing_exception",
            "document_parsing_exception", "parse_exception", "x_content_parse_exception",
            "es_rejected_execution_exception", "circuit_breaking_exception", "cluster_block_exception");

    static final class Failure extends IllegalStateException {
        final Stage stage;
        final Code code;
        final int httpStatus;
        final String sqlState;
        final String esType;

        private Failure(Stage stage, Code code, String message, int httpStatus, String sqlState, String esType) {
            super(message); // Raw exceptions, bodies, request URLs and JDBC messages must never be retained.
            this.stage = stage; this.code = code;
            this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : 0;
            this.sqlState = sqlState != null && sqlState.matches("[0-9A-Z]{5}") ? sqlState : "UNKNOWN";
            this.esType = ES_TYPES.contains(esType == null ? "" : esType) ? esType : "OTHER";
        }

        String summary() {
            return "stage=" + stage + " code=" + code + " http_status=" + httpStatus
                    + " sql_state=" + sqlState + " es_type=" + esType;
        }
    }

    static void begin(Logger log, Stage stage) { log.info("Catalog reindex stage={} outcome=started", stage); }

    static Failure failure(Stage stage, Code code, String message) {
        return details(stage, code, message, 0, null, null);
    }

    static Failure details(Stage stage, Code code, String message, int status, String sqlState, String esType) {
        return new Failure(stage, code, message, status, sqlState, esType);
    }

    static Failure report(Logger log, Stage stage, Throwable failure) {
        Failure safe;
        if (failure instanceof Failure known) {
            safe = details(stage, known.code, known.getMessage(), known.httpStatus, known.sqlState, known.esType);
        } else if (failure instanceof java.sql.SQLException sql) {
            safe = details(stage, Code.SQL_FAILED, "Catalog SQL operation failed", 0, sql.getSQLState(), null);
        } else {
            safe = failure(stage, Code.OPERATION_FAILED, "Catalog operation failed");
        }
        log.error("Catalog diagnostic: {}", safe.summary());
        return safe;
    }

    private SearchDiagnostics() { }
}
