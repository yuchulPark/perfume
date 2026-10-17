package com.perfume.ai.search;

import java.util.Map;
import javax.sql.DataSource;
import com.perfume.ai.embedding.OllamaPerfumeEmbeddingClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import com.perfume.ai.transport.RecommendationHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = PerfumeSemanticSearchOptions.ENABLED, havingValue = "true")
public class PerfumeSemanticSearchConfiguration {
    public static void configureReadOnlyExecution(SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> prepare(event.getEnvironment()));
    }
    static void prepare(ConfigurableEnvironment environment) {
        if (!environment.getProperty(PerfumeSemanticSearchOptions.ENABLED, Boolean.class, false)) { return; }
        PerfumeSemanticSearchOptions.from(environment); // Reject mixed modes/invalid identities before context or runners start.
        environment.getPropertySources().addFirst(new MapPropertySource("perfumeSemanticSearchExecution", Map.of(
                "spring.jpa.hibernate.ddl-auto", "validate", "spring.sql.init.mode", "never", "spring.jpa.show-sql", "false")));
    }
    @Bean PerfumeSemanticSearchOptions semanticSearchOptions(Environment environment) { return PerfumeSemanticSearchOptions.from(environment); }
    @Bean OllamaQueryEmbeddingAdapter queryEmbeddingAdapter(RestClient.Builder builder, PerfumeSemanticSearchOptions options) {
        var factory = new RecommendationHttpRequestFactory(options.connectTimeout(), options.readTimeout());
        return new OllamaQueryEmbeddingAdapter(new OllamaPerfumeEmbeddingClient(builder.clone().baseUrl(options.baseUrl().toString())
                .requestFactory(factory).build()), options.model());
    }
    @Bean JdbcPerfumeSemanticSearchRepository semanticSearchRepository(DataSource source, PerfumeSemanticSearchOptions options) {
        return new JdbcPerfumeSemanticSearchRepository(source, options.dbTimeoutSeconds());
    }
    @Bean(destroyMethod = "close") PerfumeSemanticSearchService semanticSearchService(OllamaQueryEmbeddingAdapter embeddings,
            JdbcPerfumeSemanticSearchRepository repository, PerfumeSemanticSearchOptions options) {
        return new PerfumeSemanticSearchService(embeddings, repository, options);
    }
}
