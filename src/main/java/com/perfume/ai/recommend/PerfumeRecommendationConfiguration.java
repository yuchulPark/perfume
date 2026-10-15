package com.perfume.ai.recommend;

import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.search.PerfumeSemanticSearchOptions;
import com.perfume.ai.search.PerfumeSemanticSearchService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = PerfumeRecommendationOptions.ENABLED, havingValue = "true")
public class PerfumeRecommendationConfiguration {
    public static void configureExecution(SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            if (event.getEnvironment().getProperty(PerfumeRecommendationOptions.ENABLED, Boolean.class, false)) {
                PerfumeRecommendationOptions.from(event.getEnvironment()); // Require search and reject mixed manual modes before context creation.
            }
        });
    }
    @Bean PerfumeRecommendationOptions recommendationOptions(Environment environment) { return PerfumeRecommendationOptions.from(environment); }
    @Bean JdbcRecommendationEvidenceRepository recommendationEvidenceRepository(DataSource source, PerfumeSemanticSearchOptions search, PerfumeRecommendationOptions options) {
        return new JdbcRecommendationEvidenceRepository(source, search.model(), options.dbTimeoutSeconds());
    }
    @Bean RecommendationPromptBuilder recommendationPromptBuilder(ObjectMapper mapper, PerfumeRecommendationOptions options) {
        return new RecommendationPromptBuilder(mapper, new RecommendationEvidenceFormatter(), options.inputBudgetBytes());
    }
    @Bean OllamaRecommendationClient recommendationLlmClient(RestClient.Builder builder, ObjectMapper mapper, PerfumeRecommendationOptions options) {
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(options.connectTimeout()); factory.setReadTimeout(options.readTimeout());
        return new OllamaRecommendationClient(builder.clone().baseUrl(options.baseUrl().toString()).requestFactory(factory).build(), mapper, options);
    }
    @Bean(destroyMethod = "close") PerfumeRecommendationService recommendationService(PerfumeSemanticSearchService search,
            JdbcRecommendationEvidenceRepository evidence, RecommendationPromptBuilder prompts, OllamaRecommendationClient llm, PerfumeRecommendationOptions options) {
        return new PerfumeRecommendationService(search, evidence, prompts, llm, options);
    }
}
