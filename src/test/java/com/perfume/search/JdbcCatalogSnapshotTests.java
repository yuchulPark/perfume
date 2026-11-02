package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

public class JdbcCatalogSnapshotTests {
    @Test void transactionIsReadOnlyRepeatableAndClosedWithoutCommitting() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        var lock = mock(PreparedStatement.class); var row = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(row); when(row.next()).thenReturn(true); when(row.getBoolean(1)).thenReturn(true);
        try (var snapshot = new JdbcCatalogSnapshot(source)) {
            var order = inOrder(connection, lock);
            order.verify(connection).setReadOnly(true);
            order.verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).prepareStatement("SELECT pg_try_advisory_xact_lock(?)");
            order.verify(lock).setQueryTimeout(30);
            order.verify(lock).setLong(1, JdbcCatalogSnapshot.LOCK_ID);
        }
        verify(connection).rollback(); verify(connection).close(); verify(connection, never()).commit();
    }
    @Test void failedLockClosesConnectionBeforeAnyCatalogRead() throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class);
        var lock = mock(PreparedStatement.class); var row = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection); when(connection.prepareStatement(anyString())).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(row); when(row.next()).thenReturn(true); when(row.getBoolean(1)).thenReturn(false);
        assertThatThrownBy(() -> new JdbcCatalogSnapshot(source)).hasMessageContaining("reindex lock");
        verify(connection).close(); verify(connection, times(1)).prepareStatement(anyString());
    }

    @Test void projectsAllNotesAndAccordsInBatchesWithoutDuplicatingPerfumes() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:catalog-snapshot;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("CREATE ALIAS pg_try_advisory_xact_lock FOR 'com.perfume.search.JdbcCatalogSnapshotTests.fixtureLock'");
        jdbc.execute("CREATE TABLE brands(id bigint primary key, name varchar, brand_slug varchar)");
        jdbc.execute("CREATE TABLE perfumes(id bigint primary key, name varchar, brand_id bigint, description varchar, release_year int)");
        jdbc.execute("CREATE TABLE notes(id bigint primary key, name varchar)");
        jdbc.execute("CREATE TABLE perfume_notes(perfume_id bigint, note_id bigint, layer varchar, position int)");
        jdbc.execute("CREATE TABLE perfume_unlayered_notes(perfume_id bigint, note_id bigint, position int)");
        jdbc.execute("CREATE TABLE accords(id bigint primary key, name varchar)");
        jdbc.execute("CREATE TABLE perfume_accords(perfume_id bigint, accord_id bigint, position int)");
        jdbc.execute("INSERT INTO brands VALUES(1, 'Creed', 'creed')");
        jdbc.execute("INSERT INTO perfumes VALUES(2, 'Aventus', 1, 'Stored', 2010), (7, 'Sparse', 1, null, null)");
        jdbc.execute("INSERT INTO notes VALUES(1, 'Rose'), (2, '장미'), (3, 'Musk')");
        jdbc.execute("INSERT INTO perfume_notes VALUES(2, 1, 'TOP', 1), (2, 2, 'TOP', 0), (2, 1, 'MIDDLE', 0), (2, 3, 'BASE', 0)");
        jdbc.execute("INSERT INTO perfume_unlayered_notes VALUES(2, 3, 0)");
        jdbc.execute("INSERT INTO accords VALUES(1, 'woody'), (2, 'citrus')");
        jdbc.execute("INSERT INTO perfume_accords VALUES(2, 1, 0), (2, 2, 1)");
        try (var snapshot = new JdbcCatalogSnapshot(source)) {
            assertThat(snapshot.count()).isEqualTo(2);
            var first = snapshot.next(0, 1).get(0);
            assertThat(first.perfumeId()).isEqualTo(2); assertThat(first.brandSlug()).isEqualTo("creed");
            assertThat(first.topNotes()).containsExactly("장미", "Rose");
            assertThat(first.middleNotes()).containsExactly("Rose"); assertThat(first.baseNotes()).containsExactly("Musk");
            assertThat(first.unlayeredNotes()).containsExactly("Musk"); assertThat(first.accords()).containsExactly("woody", "citrus");
            var second = snapshot.next(2, 1).get(0);
            assertThat(second.perfumeId()).isEqualTo(7); assertThat(second.description()).isNull(); assertThat(second.releaseYear()).isNull();
            assertThat(second.topNotes()).isEmpty(); assertThat(snapshot.next(7, 1)).isEmpty();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM perfumes", Long.class)).isEqualTo(2);
    }
    public static boolean fixtureLock(long ignored) { return true; }
}
