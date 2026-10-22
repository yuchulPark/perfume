package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class AuthActivationGuardTests {
    @Test void disabledAuthenticationNeedsNeitherAuthSchemaNorDatabaseBeans() {
        new ApplicationContextRunner().withUserConfiguration(AuthDataConfiguration.class)
            .withPropertyValues("perfume.auth.enabled=false").run(context -> {
                assertNull(context.getStartupFailure());
                assertFalse(context.containsBean("authUserStore"));
            });
    }
    @Test void activationRefusesUnsafeDdlOrInsecureProductionCookiesBeforeDatabaseAccess() {
        var store = mock(AuthUserStore.class);
        var source = mock(DataSource.class);
        for (var settings : java.util.List.of(new String[] {"update", "true"}, new String[] {"validate", "false"})) {
            var env = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", settings[0])
                .withProperty("server.servlet.session.cookie.secure", settings[1]);
            var guard = new AuthDataConfiguration().authActivationGuard(store, source, env);
            assertThrows(IllegalStateException.class, guard::afterSingletonsInstantiated);
        }
        verifyNoInteractions(source, store);
    }
    @Test void developmentProfileCannotPointAtTheExistingDatabase() {
        var env = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "none")
            .withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:15432/perfume");
        env.setActiveProfiles("auth-dev");
        var store = mock(AuthUserStore.class);
        var source = mock(DataSource.class);
        assertThrows(IllegalStateException.class, new AuthDataConfiguration().authActivationGuard(store, source, env)::afterSingletonsInstantiated);
        verifyNoInteractions(source, store);
    }
    @Test void activationFailsWhenAuthSchemaIsNotPrepared() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:missing_auth;MODE=PostgreSQL", "sa", "");
        new ApplicationContextRunner().withUserConfiguration(AuthDataConfiguration.class)
            .withBean(DataSource.class, () -> source)
            .withPropertyValues("perfume.auth.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "server.servlet.session.cookie.secure=true")
            .run(context -> {
                assertNotNull(context.getStartupFailure());
                assertTrue(context.getStartupFailure().getMessage().contains("Authentication schema is not prepared"));
            });
    }
}
