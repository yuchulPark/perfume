package com.perfume.search;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;

/** One read-only REPEATABLE READ snapshot, including all note/accord relationships. No schema writes. */
final class JdbcCatalogSnapshot implements CatalogSnapshot {
    static final long LOCK_ID = 739206174812402L;
    static final String DOCUMENTS = """
            SELECT p.id, p.name, b.name AS brand_name, b.brand_slug, p.description, p.release_year,
                ARRAY(SELECT n.name FROM public.perfume_notes pn JOIN public.notes n ON n.id = pn.note_id
                    WHERE pn.perfume_id = p.id AND pn.layer = 'TOP' ORDER BY pn.position, n.id) AS top_notes,
                ARRAY(SELECT n.name FROM public.perfume_notes pn JOIN public.notes n ON n.id = pn.note_id
                    WHERE pn.perfume_id = p.id AND pn.layer = 'MIDDLE' ORDER BY pn.position, n.id) AS middle_notes,
                ARRAY(SELECT n.name FROM public.perfume_notes pn JOIN public.notes n ON n.id = pn.note_id
                    WHERE pn.perfume_id = p.id AND pn.layer = 'BASE' ORDER BY pn.position, n.id) AS base_notes,
                ARRAY(SELECT n.name FROM public.perfume_unlayered_notes pn JOIN public.notes n ON n.id = pn.note_id
                    WHERE pn.perfume_id = p.id ORDER BY pn.position, n.id) AS unlayered_notes,
                ARRAY(SELECT a.name FROM public.perfume_accords pa JOIN public.accords a ON a.id = pa.accord_id
                    WHERE pa.perfume_id = p.id ORDER BY pa.position, a.id) AS accords
            FROM public.perfumes p JOIN public.brands b ON b.id = p.brand_id
            WHERE p.id > ? ORDER BY p.id LIMIT ?
            """;
    private final Connection connection;

    JdbcCatalogSnapshot(DataSource source) {
        Connection opened = null;
        try {
            opened = source.getConnection();
            opened.setReadOnly(true);
            opened.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            opened.setAutoCommit(false);
            // Transaction lock is shared by manual reindex processes and released by rollback/close.
            try (var statement = opened.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
                statement.setQueryTimeout(30);
                statement.setLong(1, LOCK_ID);
                try (var row = statement.executeQuery()) {
                    if (!row.next() || !row.getBoolean(1)) throw new IllegalStateException("A catalog reindex is already running");
                }
            }
            connection = opened;
        } catch (SQLException | RuntimeException failure) {
            if (opened != null) { try { opened.close(); } catch (SQLException ignored) { } }
            throw new IllegalStateException("Cannot open catalog snapshot or acquire reindex lock");
        }
    }

    @Override public long count() {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM public.perfumes")) {
            statement.setQueryTimeout(30);
            try (var row = statement.executeQuery()) { row.next(); return row.getLong(1); }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot count catalog snapshot"); }
    }

    @Override public List<PerfumeSearchDocument> next(long afterId, int size) {
        try (var statement = connection.prepareStatement(DOCUMENTS)) {
            statement.setQueryTimeout(30);
            statement.setLong(1, afterId); statement.setInt(2, size);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<PerfumeSearchDocument>();
                while (rows.next()) result.add(new PerfumeSearchDocument(rows.getLong("id"), rows.getString("name"),
                        rows.getString("brand_name"), rows.getString("brand_slug"), rows.getString("description"),
                        rows.getObject("release_year", Integer.class), array(rows, "top_notes"), array(rows, "middle_notes"),
                        array(rows, "base_notes"), array(rows, "unlayered_notes"), array(rows, "accords")));
                return result;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read catalog snapshot"); }
    }

    private static List<String> array(ResultSet rows, String column) throws SQLException {
        var value = rows.getArray(column);
        if (value == null) return List.of();
        try { return Arrays.stream((Object[]) value.getArray()).map(Object::toString).toList(); }
        finally { value.free(); }
    }

    @Override public void close() {
        try { connection.rollback(); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot close catalog snapshot"); }
        finally {
            try { connection.close(); }
            catch (SQLException failure) { throw new IllegalStateException("Cannot close catalog connection"); }
        }
    }
}
