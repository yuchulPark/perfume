package com.perfume.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import com.perfume.auth.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Test classpath only: never reads DB_URL, .env, server credentials or any existing data volume. */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "support.test.fixture", havingValue = "true")
@EnableAutoConfiguration(exclude = {org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class})
@Import({AuthSecurityConfiguration.class, AuthController.class, SupportConfiguration.class, SupportSocketConfiguration.class,
    SupportController.class, SupportMessaging.class, SupportTestApplication.Pages.class})
public class SupportTestApplication {
    static final String PASSWORD = System.getenv().getOrDefault("SUPPORT_TEST_PASSWORD", "Test-" + UUID.randomUUID() + "Az!");
    @Bean DataSource source() throws Exception {
        String data = System.getenv("SUPPORT_TEST_PGDATA");
        DriverManagerDataSource source;
        if (data != null) {
            Path expected = Path.of("target/support-local/data").toRealPath();
            if (!expected.equals(Path.of(data).toRealPath())) throw new IllegalStateException("Unexpected isolated support cluster");
            source = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:26432/perfume_support_test", "perfume_support_test", "");
            var probe = new JdbcTemplate(source);
            if (!expected.equals(Path.of(probe.queryForObject("SHOW data_directory", String.class)).toRealPath())
                || !"perfume_support_test".equals(probe.queryForObject("SELECT current_database()", String.class))) throw new IllegalStateException("Physical isolation check failed");
        } else source = new DriverManagerDataSource("jdbc:h2:mem:support_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        var jdbc = new JdbcTemplate(source);
        String ddl = Files.readString(Path.of("docs/sql/support-schema.sql"));
        if (data == null) ddl = ddl.replace("timestamptz", "timestamp with time zone");
        jdbc.execute(ddl);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("CREATE TABLE IF NOT EXISTS auth.app_users(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, username varchar(64) UNIQUE NOT NULL, password_hash varchar(60) NOT NULL, role varchar(16) NOT NULL, enabled boolean NOT NULL)");
        if (jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Integer.class) == 0)
            new AuthUserStore(source, new BCryptPasswordEncoder(10)).createAdministrator("admin", PASSWORD, false);
        return source;
    }
    @Bean AuthUserStore users(DataSource source) { return new AuthUserStore(source, new BCryptPasswordEncoder(10)); }
    @org.springframework.stereotype.Controller
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "support.test.fixture", havingValue = "true")
    public static class Pages {
        @org.springframework.web.bind.annotation.GetMapping({"/", "/login", "/recommend", "/admin/support", "/perfumes/{id}"})
        String page() { return "forward:/index.html"; }
    }
    public static void main(String[] args) {
        if (!"true".equals(System.getenv("SUPPORT_LOCAL_TEST")) || System.getenv("SUPPORT_TEST_PGDATA") == null)
            throw new IllegalStateException("Explicit isolated PostgreSQL test gate required");
        var app = new SpringApplication(SupportTestApplication.class);
        Map<String,String> settings = Map.ofEntries(Map.entry("support.test.fixture", "true"), Map.entry("server.address", "127.0.0.1"), Map.entry("server.port", "18082"), Map.entry("perfume.auth.enabled", "true"),
            Map.entry("server.servlet.session.cookie.secure", "false"), Map.entry("perfume.support.enabled", "true"), Map.entry("perfume.support.allowed-origins", "http://127.0.0.1:18082"),
            Map.entry("spring.jpa.hibernate.ddl-auto", "none"), Map.entry("spring.web.resources.static-locations", "file:frontend/dist/"), Map.entry("spring.sql.init.mode", "never"),
            Map.entry("debug", "false"), Map.entry("logging.level.root", "WARN"));
        app.run(settings.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
    }
}
