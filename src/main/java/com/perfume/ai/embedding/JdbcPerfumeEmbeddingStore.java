package com.perfume.ai.embedding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Only reads public catalog tables. AI writes are atomic upserts on a dedicated locked connection. */
public class JdbcPerfumeEmbeddingStore implements PerfumeEmbeddingStore {
    // One database-wide AI batch, even for different models or different JVMs.
    static final long LOCK_ID = 739206174812401L;
    static final String UPSERT = """
            INSERT INTO ai.perfume_embeddings AS existing
                (perfume_id, model_name, model_digest, text_version, source_text, source_sha256, embedding)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS public.vector(1024)))
            ON CONFLICT (perfume_id, model_name, model_digest, text_version) DO UPDATE
            SET source_text = EXCLUDED.source_text, source_sha256 = EXCLUDED.source_sha256,
                embedding = EXCLUDED.embedding, updated_at = CURRENT_TIMESTAMP
            WHERE existing.source_sha256 IS DISTINCT FROM EXCLUDED.source_sha256
            """;
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcPerfumeEmbeddingStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    }

    @Override
    public Session open() {
        Connection connection;
        try { connection = dataSource.getConnection(); }
        catch (SQLException failure) { throw databaseFailure(); }
        var session = new JdbcSession(connection);
        try {
            if (!connection.getAutoCommit()) { throw databaseFailure(); }
            session.locked = Boolean.TRUE.equals(session.jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_ID));
            if (!session.locked) { throw new EmbeddingFailure(EmbeddingFailure.Code.BATCH_BUSY); }
            session.verifySchema();
            return session;
        } catch (SQLException | RuntimeException failure) {
            session.close();
            if (failure instanceof EmbeddingFailure known) { throw known; }
            throw databaseFailure();
        }
    }

    private final class JdbcSession implements Session {
        private final Connection connection;
        private final JdbcTemplate jdbc;
        private final TransactionTemplate snapshot;
        private boolean locked;
        private boolean closed;

        private JdbcSession(Connection connection) {
            this.connection = connection;
            var single = new SingleConnectionDataSource(connection, true);
            jdbc = new JdbcTemplate(single);
            jdbc.setQueryTimeout(30);
            var manager = new DataSourceTransactionManager(single);
            manager.setEnforceReadOnly(true);
            snapshot = new TransactionTemplate(manager);
            snapshot.setReadOnly(true);
            snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            snapshot.setTimeout(30);
        }

        private void verifySchema() {
            var valid = jdbc.queryForObject("""
                    SELECT current_database() = 'perfume' AND current_user = 'perfume_user'
                        AND current_setting('server_version_num')::integer BETWEEN 170000 AND 179999
                        AND EXISTS (SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                            WHERE e.extname = 'vector' AND e.extversion = '0.8.7' AND n.nspname = 'public')
                        AND EXISTS (SELECT 1 FROM pg_attribute
                            WHERE attrelid = 'ai.perfume_embeddings'::regclass AND attname = 'embedding'
                                AND atttypid = 'public.vector'::regtype AND atttypmod = 1024 AND NOT attisdropped)
                        AND EXISTS (SELECT 1 FROM pg_constraint c
                            WHERE c.conrelid = 'ai.perfume_embeddings'::regclass AND c.contype = 'p'
                                AND (SELECT array_agg(a.attname::text ORDER BY k.position)
                                    FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, position)
                                    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum)
                                    = ARRAY['perfume_id','model_name','model_digest','text_version'])
                    """, Boolean.class);
            if (!Boolean.TRUE.equals(valid)) { throw databaseFailure(); }
            jdbc.queryForList("""
                    SELECT perfume_id, model_name, model_digest, text_version, source_text, source_sha256,
                        embedding, created_at, updated_at FROM ai.perfume_embeddings WHERE false
                    """);
        }

        @Override
        public Window window(long afterId) {
            try {
                return jdbc.queryForObject("SELECT coalesce(max(id), 0) AS upper_id, count(*) AS available FROM public.perfumes WHERE id > ?",
                        (rs, row) -> new Window(rs.getLong("upper_id"), rs.getLong("available")), afterId);
            } catch (DataAccessException failure) { throw databaseFailure(); }
        }

        @Override
        public List<Long> nextIds(long afterId, long upperId, int limit) {
            try {
                return jdbc.queryForList("SELECT id FROM public.perfumes WHERE id > ? AND id <= ? ORDER BY id LIMIT ?",
                        Long.class, afterId, upperId, limit);
            } catch (DataAccessException failure) { throw databaseFailure(); }
        }

        @Override
        public PerfumeEmbeddingDocument load(long id) {
            try {
                return snapshot.execute(status -> {
                    var identities = jdbc.query("""
                            SELECT p.id, p.name, b.name AS brand_name, p.description, p.release_year, p.reviews_count
                            FROM public.perfumes p JOIN public.brands b ON b.id = p.brand_id WHERE p.id = ?
                            """, (rs, row) -> new PerfumeEmbeddingDocument(rs.getLong("id"), rs.getString("name"),
                                rs.getString("brand_name"), rs.getString("description"), rs.getObject("release_year", Integer.class), rs.getObject("reviews_count", Long.class),
                                List.of(), List.of(), List.of()), id);
                    if (identities.size() != 1) { throw new EmbeddingFailure(EmbeddingFailure.Code.SOURCE_MISSING); }
                    var base = identities.get(0);
                    var notes = jdbc.query("""
                            SELECT pn.layer, n.name, pn.position FROM public.perfume_notes pn
                            JOIN public.notes n ON n.id = pn.note_id WHERE pn.perfume_id = ?
                            UNION ALL
                            SELECT 'UNLAYERED' AS layer, n.name, pn.position FROM public.perfume_unlayered_notes pn
                            JOIN public.notes n ON n.id = pn.note_id WHERE pn.perfume_id = ?
                            """, (rs, row) -> new PerfumeEmbeddingDocument.Note(rs.getString("layer"), rs.getString("name"),
                                rs.getObject("position", Integer.class)), id, id);
                    var accords = jdbc.query("""
                            SELECT a.name, pa.percentage, pa.score, pa.position FROM public.perfume_accords pa
                            JOIN public.accords a ON a.id = pa.accord_id WHERE pa.perfume_id = ?
                            """, (rs, row) -> new PerfumeEmbeddingDocument.Accord(rs.getString("name"),
                                rs.getObject("percentage", Integer.class), rs.getBigDecimal("score"), rs.getObject("position", Integer.class)), id);
                    var metrics = jdbc.query("""
                            SELECT metric_key, score, category, n_records, reliability, scale, derived_from,
                                reviews_count, label, details FROM public.perfume_metrics WHERE perfume_id = ?
                            """, (rs, row) -> {
                                try {
                                    var details = rs.getString("details");
                                    return new PerfumeEmbeddingDocument.Metric(rs.getString("metric_key"), rs.getBigDecimal("score"),
                                            rs.getString("category"), rs.getObject("n_records", Long.class), rs.getString("reliability"),
                                            rs.getString("scale"), rs.getString("derived_from"), rs.getObject("reviews_count", Long.class),
                                            rs.getString("label"), details == null ? null : mapper.readTree(details));
                                } catch (JsonProcessingException invalid) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_SOURCE); }
                            }, id);
                    return new PerfumeEmbeddingDocument(id, base.name(), base.brand(), base.description(), base.releaseYear(), base.reviewsCount(), notes, accords, metrics);
                });
            } catch (DataAccessException failure) { throw databaseFailure(); }
        }

        @Override
        public Optional<String> findHash(long id, EmbeddingModelIdentity model) {
            try {
                var hashes = jdbc.queryForList("""
                        SELECT source_sha256 FROM ai.perfume_embeddings
                        WHERE perfume_id = ? AND model_name = ? AND model_digest = ? AND text_version = ?
                        """, String.class, id, model.name(), model.digest(), model.textVersion());
                return hashes.stream().findFirst();
            } catch (DataAccessException failure) { throw databaseFailure(); }
        }

        @Override
        public boolean save(long id, EmbeddingModelIdentity model, String text, String hash, float[] vector) {
            if (!new PerfumeEmbeddingText().hash(text).equals(hash)) { throw new EmbeddingFailure(EmbeddingFailure.Code.INVALID_SOURCE); }
            var literal = EmbeddingVector.literal(vector);
            try {
                // No transaction spans the HTTP request. Each successful upsert commits independently.
                return jdbc.update(UPSERT, id, model.name(), model.digest(), model.textVersion(), text, hash, literal) == 1;
            } catch (DataAccessException failure) { throw databaseFailure(); }
        }

        @Override
        public void close() {
            if (closed) { return; }
            closed = true;
            boolean unsafe = false;
            try {
                if (locked && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_ID))) {
                    unsafe = true;
                }
            } catch (RuntimeException failure) { unsafe = true; }
            finally {
                try {
                    // Returning a pooled connection is insufficient if the session lock was not released.
                    if (unsafe) { connection.abort(Runnable::run); }
                } catch (SQLException ignored) { unsafe = true; }
                finally {
                    try { connection.close(); }
                    catch (SQLException ignored) { unsafe = true; }
                }
            }
            if (unsafe) { throw databaseFailure(); }
        }
    }

    private static EmbeddingFailure databaseFailure() { return new EmbeddingFailure(EmbeddingFailure.Code.DATABASE); }
}
