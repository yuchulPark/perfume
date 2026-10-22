package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthUserStoreTests {
    DataSource source;
    AuthUserStore store;
    BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    static void schema(DataSource source) {
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        // H2 fixture: role uniqueness models the PostgreSQL partial ADMIN index for this ADMIN-only release.
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.app_users(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, username varchar(64) UNIQUE NOT NULL, password_hash varchar(60) NOT NULL, role varchar(16) UNIQUE NOT NULL, enabled boolean NOT NULL, created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_at timestamp DEFAULT CURRENT_TIMESTAMP)");
    }
    @BeforeEach void database() {
        source = new DriverManagerDataSource("jdbc:h2:mem:auth_store_" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        schema(source);
        store = new AuthUserStore(source, encoder);
    }
    @Test void devBootstrapCreatesExactlyOneSaltedBcryptAccountAndNeverAllowsReplayOrDuplicates() {
        String id = "admin";
        store.createAdministrator(id, id, true);
        var user = store.loadUser(id);
        assertTrue(encoder.matches(id, user.getPassword()));
        assertNotEquals(id, user.getPassword());
        assertNotEquals(encoder.encode(id), user.getPassword(), "BCrypt salts must not be fixed");
        assertThrows(IllegalStateException.class, () -> store.createAdministrator(id, id, true));
        assertThrows(IllegalStateException.class, () -> store.createAdministrator("another", id, true));
        assertEquals(1, new JdbcTemplate(source).queryForObject("SELECT count(*) FROM auth.app_users", Integer.class));
        store.verifyReady(true);
        assertThrows(IllegalStateException.class, () -> store.verifyReady(false));
    }
    @Test void missingAdminAndWeakProductionPasswordsRefuseActivation() {
        assertThrows(IllegalStateException.class, () -> store.verifyReady(false));
        String id = "admin";
        assertThrows(IllegalArgumentException.class, () -> store.createAdministrator(id, id, false));
        String strong = java.util.UUID.randomUUID() + "Az!";
        store.createAdministrator("operator", strong, false);
        store.verifyReady(false);
        assertTrue(encoder.matches(strong, store.loadUser("operator").getPassword()));
    }
    @Test void devModeRejectsNormalDatabaseAndSshTunnelAddressesBeforeConnecting() {
        for (String url : java.util.List.of("jdbc:postgresql://localhost:5432/perfume", "jdbc:postgresql://127.0.0.1:15432/perfume", "jdbc:postgresql://db:5432/perfume"))
            assertThrows(IllegalStateException.class, () -> AuthDataConfiguration.verifyIsolatedDatabase(null, url));
    }
    @Test void concurrentBootstrapCannotCreateTwoAdministrators() throws Exception {
        try (var executor = new AutoClosingExecutor()) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.pool.submit(() -> {
                try { store.createAdministrator("operator" + i, java.util.UUID.randomUUID() + "Az!", false); return true; }
                catch (IllegalStateException closed) { return false; }
            })).toList();
            int successes = 0;
            for (var future : futures) if (future.get()) successes++;
            assertEquals(1, successes);
        }
    }
    static class AutoClosingExecutor implements AutoCloseable {
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        public void close() { pool.shutdownNow(); }
    }
}
