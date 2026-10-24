package com.perfume.support;

import java.net.URI;
import java.util.Arrays;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "perfume.support.enabled", havingValue = "true")
public class SupportConfiguration {
    @Bean SupportStore supportStore(DataSource source) { return new SupportStore(source); }
    static String[] origins(Environment env) {
        String configured = env.getProperty("perfume.support.allowed-origins", "");
        String[] origins = Arrays.stream(configured.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (origins.length == 0 || origins.length > 5) throw new IllegalStateException("Support requires an explicit origin allowlist");
        for (String origin : origins) {
            URI uri;
            try { uri = URI.create(origin); } catch (IllegalArgumentException ex) { throw new IllegalStateException("Invalid support origin"); }
            if (!Arrays.asList("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())
                || origin.contains("*") || origin.endsWith("/")) throw new IllegalStateException("Support origins must be exact scheme/host/port origins");
        }
        return origins;
    }
    @Bean SmartInitializingSingleton supportActivationGuard(SupportStore store, Environment env) {
        return () -> {
            if (!env.getProperty("perfume.auth.enabled", Boolean.class, false)) throw new IllegalStateException("Support requires existing session authentication");
            if (!Arrays.asList("none", "validate").contains(env.getProperty("spring.jpa.hibernate.ddl-auto", "")))
                throw new IllegalStateException("Support requires ddl-auto=none or validate");
            origins(env);
            try { store.verifyReady(); }
            catch (org.springframework.dao.DataAccessException ex) { throw new IllegalStateException("Support schema is not ready; apply the reviewed support-only SQL manually"); }
        };
    }
}
