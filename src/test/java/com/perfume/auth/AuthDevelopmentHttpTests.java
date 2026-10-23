package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuthDevelopmentHttpTests {
    static MockEnvironment settings() {
        var env = new MockEnvironment().withProperty("perfume.auth.enabled", "true")
            .withProperty("perfume.auth.dev-http.enabled", "true").withProperty("perfume.auth.dev-http.origin", AuthDevelopmentHttp.ORIGIN)
            .withProperty("spring.datasource.url", "jdbc:postgresql://db:5432/perfume")
            .withProperty("server.servlet.session.cookie.secure", "false").withProperty("server.servlet.session.cookie.http-only", "true")
            .withProperty("server.servlet.session.cookie.name", "PERFUME_DEV_SESSION")
            .withProperty("server.servlet.session.cookie.same-site", "lax").withProperty("server.servlet.session.tracking-modes", "cookie")
            .withProperty("spring.jpa.hibernate.ddl-auto", "validate");
        env.setActiveProfiles(AuthDevelopmentHttp.PROFILE);
        return env;
    }

    @Test void defaultDoesNotEnableHttpAndFlagOrProfileAloneCannotEnableIt() {
        assertFalse(AuthDevelopmentHttp.enabled(new MockEnvironment()));
        var flagOnly = settings(); flagOnly.setActiveProfiles();
        assertThrows(IllegalStateException.class, () -> AuthDevelopmentHttp.enabled(flagOnly));
        var profileOnly = settings().withProperty("perfume.auth.dev-http.enabled", "false");
        assertThrows(IllegalStateException.class, () -> AuthDevelopmentHttp.enabled(profileOnly));
        assertTrue(AuthDevelopmentHttp.enabled(settings()));
    }

    @Test void publicOriginsMixedProfilesAndUnsafeCookiesFailBeforeDatabaseAccess() {
        var source = mock(DataSource.class); var store = mock(AuthUserStore.class);
        for (String[] setting : List.of(new String[]{"perfume.auth.dev-http.origin", "http://example.com:8088"},
                new String[]{"perfume.auth.dev-http.origin", "http://0.0.0.0:8088"},
                new String[]{"perfume.auth.dev-http.origin", "https://192.168.0.19:8443"},
                new String[]{"perfume.auth.enabled", "false"},
                new String[]{"spring.datasource.url", "jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev"},
                new String[]{"server.servlet.session.cookie.http-only", "false"},
                new String[]{"server.servlet.session.cookie.name", "JSESSIONID"},
                new String[]{"server.servlet.session.cookie.same-site", "none"},
                new String[]{"server.servlet.session.tracking-modes", "url"},
                new String[]{"server.servlet.session.cookie.secure", "true"},
                new String[]{"spring.jpa.hibernate.ddl-auto", "update"})) {
            var env = settings().withProperty(setting[0], setting[1]);
            assertThrows(IllegalStateException.class, new AuthDataConfiguration().authActivationGuard(store, source, env)::afterSingletonsInstantiated);
        }
        for (String other : List.of("prod", "production", "auth-dev", "default")) {
            var env = settings(); env.setActiveProfiles(AuthDevelopmentHttp.PROFILE, other);
            assertThrows(IllegalStateException.class, () -> AuthDevelopmentHttp.enabled(env));
        }
        verifyNoInteractions(source, store);
    }

    @Test void privateProxyAndClientChecksDoNotResolveHostnamesOrAcceptPublicAddresses() {
        for (String address : List.of("127.0.0.1", "::1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.0.2"))
            assertTrue(AuthDevelopmentHttp.privateAddress(address), address);
        for (String address : List.of("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "0.0.0.0", "localhost", "127.1", "127.00.0.1", "10.0.0.999", "192.168.0.1, 8.8.8.8", "2001:4860:4860::8888"))
            assertFalse(AuthDevelopmentHttp.privateAddress(address), address);
        assertFalse(AuthDevelopmentHttp.privateAddress(null));
    }

    @Test void httpProfileStillRejectsDefaultAdministratorPassword() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:http_guard;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        AuthUserStoreTests.schema(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE pg_indexes(schemaname varchar, tablename varchar, indexname varchar, indexdef varchar)");
        jdbc.update("INSERT INTO pg_indexes VALUES ('auth','app_users','app_users_single_admin',?)", "CREATE UNIQUE INDEX app_users_single_admin ON auth.app_users (role) WHERE role='ADMIN'");
        var store = new AuthUserStore(source, new BCryptPasswordEncoder(10));
        store.createAdministrator("admin", "admin", true);
        var guard = new AuthDataConfiguration().authActivationGuard(store, source, settings());
        assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
        jdbc.update("DELETE FROM auth.app_users");
        store.createAdministrator("admin", java.util.UUID.randomUUID()+"Az!", false);
        assertDoesNotThrow(guard::afterSingletonsInstantiated);
    }
}
