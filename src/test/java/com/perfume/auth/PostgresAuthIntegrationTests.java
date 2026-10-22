package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Explicitly gated to a newly initialized cluster under this workspace; never the existing DB or SSH tunnel. */
@EnabledIfEnvironmentVariable(named = "PERFUME_AUTH_TEST_PGDATA", matches = ".+")
class PostgresAuthIntegrationTests {
    @Test void actualPostgresDdlSingleAdminConcurrencyAndDevelopmentAuthentication() throws Exception {
        Path expected = Path.of("target/auth-postgres/data").toRealPath();
        assertEquals(expected, Path.of(System.getenv("PERFUME_AUTH_TEST_PGDATA")).toRealPath());
        String url = "jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev";
        var source = new DriverManagerDataSource(url, "perfume_auth_dev", "");
        var jdbc = new JdbcTemplate(source);
        // Verify physical cluster identity before every mutation in this test is authorized.
        assertEquals(expected, Path.of(jdbc.queryForObject("SHOW data_directory", String.class)).toRealPath());
        AuthDataConfiguration.verifyIsolatedDatabase(source, url);
        AuthDataConfiguration.verifySchema(source);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Long.class));
        var encoder = new BCryptPasswordEncoder(10);
        var store = new AuthUserStore(source, encoder);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                try { store.createAdministrator("operator" + i, java.util.UUID.randomUUID() + "Az!", false); return true; }
                catch (IllegalStateException closed) { return false; }
            })).toList();
            int successes = 0;
            for (var future : futures) if (future.get()) successes++;
            assertEquals(1, successes);
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Long.class));
        } finally { executor.shutdownNow(); }
        // This delete is confined to the verified new workspace cluster, never a pre-existing DB.
        jdbc.update("DELETE FROM auth.app_users");
        String id = "admin";
        store.createAdministrator(id, id, true);
        assertTrue(encoder.matches(id, store.loadUser(id).getPassword()));
        assertNotEquals(id, jdbc.queryForObject("SELECT password_hash FROM auth.app_users", String.class));
        assertThrows(IllegalStateException.class, () -> store.createAdministrator("another", id, true));
        var provider = new DaoAuthenticationProvider(store::loadUser);
        provider.setPasswordEncoder(encoder);
        assertTrue(provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(id, id)).isAuthenticated());
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(id, java.util.UUID.randomUUID().toString())));
        assertThrows(BadCredentialsException.class, () -> provider.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("missing", id)));
        var env = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "none").withProperty("spring.datasource.url", url);
        env.setActiveProfiles("auth-dev");
        new AuthDataConfiguration().authActivationGuard(store, source, env).afterSingletonsInstantiated();
        assertThrows(IllegalStateException.class, () -> store.verifyReady(false));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname IN ('public', 'ai')", Long.class));
    }
}
