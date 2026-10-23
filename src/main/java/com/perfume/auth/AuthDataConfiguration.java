package com.perfume.auth;

import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "perfume.auth.enabled", havingValue = "true")
public class AuthDataConfiguration {
    @Bean AuthUserStore authUserStore(DataSource source) { return new AuthUserStore(source, new BCryptPasswordEncoder(12)); }

    @Bean SmartInitializingSingleton authActivationGuard(AuthUserStore store, DataSource source, Environment environment) {
        return () -> {
            boolean dev = environment.matchesProfiles("auth-dev");
            boolean devHttp = AuthDevelopmentHttp.enabled(environment);
            String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "");
            if (!ddl.equals("validate") && !ddl.equals("none")) throw new IllegalStateException("Authentication requires ddl-auto=validate or none");
            if (dev) verifyIsolatedDatabase(source, environment.getProperty("spring.datasource.url", ""));
            else if (!devHttp && !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false))
                throw new IllegalStateException("Authentication requires Secure session cookies outside isolated development");
            try { verifySchema(source); store.verifyReady(dev); }
            catch (org.springframework.dao.DataAccessException ex) {
                throw new IllegalStateException("Authentication schema is not prepared; apply the reviewed auth-only SQL before activation");
            }
        };
    }
    static void verifySchema(DataSource source) {
        var definitions = new JdbcTemplate(source).queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'auth' AND tablename = 'app_users' AND indexname = 'app_users_single_admin'", String.class);
        if (definitions.size() != 1 || !definitions.get(0).contains("UNIQUE INDEX")
            || !definitions.get(0).contains("(role)") || !definitions.get(0).contains("WHERE") || !definitions.get(0).contains("'ADMIN'"))
            throw new IllegalStateException("Authentication requires the reviewed single-administrator unique index");
    }
    static void verifyIsolatedDatabase(DataSource source, String url) {
        if (!url.equals("jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev"))
            throw new IllegalStateException("Development authentication requires the dedicated local database on port 25432");
        var jdbc = new JdbcTemplate(source);
        if (!"perfume_auth_dev".equals(jdbc.queryForObject("SELECT current_database()", String.class))
            || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT isolated FROM auth.development_only WHERE id = 1", Boolean.class)))
            throw new IllegalStateException("The isolated development database marker is missing");
    }
}
