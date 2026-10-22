package com.perfume.auth;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import com.perfume.domain.Brand;
import com.perfume.domain.Perfume;
import com.perfume.repository.BrandRepository;
import com.perfume.repository.PerfumeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ContextConfiguration;

/** Explicit fixture for a new physical cluster, never an existing catalog or forwarded database. */
@EnabledIfEnvironmentVariable(named = "PERFUME_AUTH_LOCAL_CHECK", matches = "true")
@ContextConfiguration(initializers = AuthLocalVerificationSetupTests.IsolationCheck.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev",
    "spring.datasource.username=perfume_auth_dev", "spring.datasource.password=",
    "perfume.auth.enabled=false", "perfume.ai.search.enabled=false", "perfume.ai.recommend.enabled=false",
    "perfume.ai.embedding.batch.enabled=false", "perfume.ai.embedding.auto.enabled=false", "scentrev.brand-import=false",
    "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.show-sql=false", "spring.sql.init.mode=never",
    "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/auth-local-check/catalog-schema.sql"
})
class AuthLocalVerificationSetupTests {
    static final String URL = "jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev";
    static DriverManagerDataSource verifiedSource() throws Exception {
        Path expected = Path.of("target/auth-local-check/data").toRealPath();
        assertEquals(expected, Path.of(System.getenv("PERFUME_AUTH_LOCAL_PGDATA")).toRealPath());
        var source = new DriverManagerDataSource(URL, "perfume_auth_dev", "");
        var jdbc = new JdbcTemplate(source);
        assertEquals(expected, Path.of(jdbc.queryForObject("SHOW data_directory", String.class)).toRealPath());
        assertEquals("127.0.0.1", jdbc.queryForObject("SELECT host(inet_server_addr())", String.class));
        assertEquals(25432, jdbc.queryForObject("SELECT inet_server_port()", Integer.class));
        AuthDataConfiguration.verifyIsolatedDatabase(source, URL);
        return source;
    }
    static class IsolationCheck implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        public void initialize(ConfigurableApplicationContext context) {
            try { verifiedSource(); }
            catch (Exception failure) { throw new IllegalStateException("Local verification cluster identity could not be proven; setup stopped", failure); }
        }
    }
    @Autowired BrandRepository brands;
    @Autowired PerfumeRepository perfumes;

    @Test void prepareFreshIsolatedCatalogAndSingleDevelopmentAdministrator() throws Exception {
        var source = verifiedSource();
        var jdbc = new JdbcTemplate(source);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM pg_tables WHERE schemaname IN ('public', 'ai')", Long.class),
            "Refuse to modify any existing catalog tables");
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Long.class), "Never replace an existing administrator");
        var ddl = Path.of("target/auth-local-check/catalog-schema.sql");
        assertTrue(Files.size(ddl) > 0);
        new ResourceDatabasePopulator(new FileSystemResource(ddl)).execute(source);
        var brand = brands.saveAndFlush(new Brand("LOCAL AUTH CHECK", "local-auth-check"));
        perfumes.saveAndFlush(new Perfume("local-auth-check-1", "local-citrus-check", "로컬 검증용 시트러스", 2026,
            "인증 검증을 위해 만든 합성 향수입니다. 운영 데이터와 무관합니다.", 0L, brand));
        perfumes.saveAndFlush(new Perfume("local-auth-check-2", "local-woody-check", "로컬 검증용 우디", 2026,
            "비로그인 목록과 상세 화면 검증용 합성 향수입니다.", 0L, brand));
        var encoder = new BCryptPasswordEncoder(12);
        String bootstrap = UUID.randomUUID().toString();
        AuthAdminCli.verifyBootstrapCredential(encoder.encode(bootstrap), bootstrap, encoder);
        var store = new AuthUserStore(source, encoder);
        String username = "admin";
        store.createAdministrator(username, username, true);
        assertTrue(encoder.matches(username, store.loadUser(username).getPassword()));
        assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM auth.app_users", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT count(*) FROM public.perfumes", Long.class));
        assertThrows(IllegalStateException.class, () -> store.createAdministrator("another", username, true));
        store.verifyReady(true);
        AuthDataConfiguration.verifySchema(source);
    }
}
