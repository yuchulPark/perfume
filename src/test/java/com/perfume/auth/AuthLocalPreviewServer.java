package com.perfume.auth;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Test-classpath-only launcher: serve the built React app and real API on one loopback origin. */
public final class AuthLocalPreviewServer {
    public static void main(String[] args) throws Exception {
        if (!"true".equals(System.getenv("PERFUME_AUTH_LOCAL_CHECK"))) throw new IllegalStateException("Local verification gate is closed");
        String url = "jdbc:postgresql://127.0.0.1:25432/perfume_auth_dev";
        if (!url.equals(System.getenv("DB_URL"))) throw new IllegalStateException("Unexpected local verification DB URL");
        var source = new DriverManagerDataSource(url, "perfume_auth_dev", "");
        var jdbc = new JdbcTemplate(source);
        Path expected = Path.of("target/auth-local-check/data").toRealPath();
        if (!expected.equals(Path.of(jdbc.queryForObject("SHOW data_directory", String.class)).toRealPath()))
            throw new IllegalStateException("Physical verification cluster mismatch; refusing startup");
        AuthDataConfiguration.verifyIsolatedDatabase(source, url);
        AuthDataConfiguration.verifySchema(source);
        var application = new SpringApplication(ServerApplication.class);
        application.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "perfume.auth.local-preview", havingValue = "true")
    @EnableAutoConfiguration
    @EntityScan(basePackages = {"com.perfume.domain", "com.perfume.scentrev.progress"})
    @EnableJpaRepositories(basePackages = {"com.perfume.repository", "com.perfume.scentrev.progress"})
    @ComponentScan(basePackages = "com.perfume", excludeFilters = @ComponentScan.Filter(type = FilterType.CUSTOM, classes = OnlyProductionComponents.class))
    @Import(SpaPages.class)
    public static class ServerApplication { }

    public static class OnlyProductionComponents implements TypeFilter {
        public boolean match(MetadataReader metadata, MetadataReaderFactory factory) throws java.io.IOException {
            return metadata.getClassMetadata().getClassName().equals("com.perfume.PerfumeApplication")
                || metadata.getResource().getURL().toString().replace('\\', '/').contains("/target/test-classes/");
        }
    }

    @Controller
    @ConditionalOnProperty(name = "perfume.auth.local-preview", havingValue = "true")
    public static class SpaPages {
        @GetMapping({"/", "/login", "/recommend", "/perfumes/{id}"})
        public String page() { return "forward:/index.html"; }
    }
}
