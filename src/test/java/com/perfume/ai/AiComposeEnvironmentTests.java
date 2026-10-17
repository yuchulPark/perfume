package com.perfume.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.embedding.PerfumeEmbeddingConfiguration;
import com.perfume.ai.embedding.PerfumeEmbeddingStore;
import com.perfume.ai.recommend.PerfumeRecommendationConfiguration;
import com.perfume.ai.recommend.PerfumeRecommendationOptions;
import com.perfume.ai.recommend.PerfumeRecommendationService;
import com.perfume.ai.search.PerfumeSemanticSearchConfiguration;
import com.perfume.ai.search.PerfumeSemanticSearchOptions;
import com.perfume.ai.search.PerfumeSemanticSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.web.client.RestClient;

/** Real Spring environment-name resolution and startup gates; no network or JDBC calls. */
class AiComposeEnvironmentTests {
    @Test void uppercaseComposeNamesOverrideApplicationDefaultsAndResolveHyphenatedOptions() throws Exception {
        var environment = environment(enabled());
        assertThat(environment.getProperty("perfume.ai.search.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("perfume.ai.recommend.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("perfume.ai.search.model-digest")).isEqualTo("a".repeat(64));
        assertThat(environment.getProperty("perfume.ai.recommend.model-digest")).isEqualTo("sha256:" + "b".repeat(64));
        var search = PerfumeSemanticSearchOptions.from(environment);
        var recommend = PerfumeRecommendationOptions.from(environment);
        assertThat(search.model().name()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(search.model().digest()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(search.readTimeout().toMillis()).isEqualTo(60000);
        assertThat(search.requestTimeout().toMillis()).isEqualTo(120000);
        assertThat(search.maxConcurrent()).isEqualTo(1);
        assertThat(recommend.model()).isEqualTo("qwen3:4b-instruct");
        assertThat(recommend.modelDigest()).isEqualTo("sha256:" + "b".repeat(64));
        assertThat(recommend.readTimeout().toMillis()).isEqualTo(300000);
        assertThat(recommend.generationTimeout().toMillis()).isEqualTo(300000);
        assertThat(recommend.requestTimeout().toMillis()).isEqualTo(420000);
        assertThat(recommend.maxConcurrent()).isEqualTo(1);
        assertThat(environment.getProperty("perfume.ai.embedding.auto.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("perfume.ai.embedding.batch.enabled", Boolean.class)).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualConditionalBeansRespectComposeEnvironmentFlagsWithoutCallingDatabaseOrModels(boolean enabled) {
        var inputs = enabled();
        if (!enabled) {
            inputs.put("PERFUME_AI_SEARCH_ENABLED", "false"); inputs.put("PERFUME_AI_RECOMMEND_ENABLED", "false");
            inputs.put("PERFUME_AI_SEARCH_MODEL_DIGEST", ""); inputs.put("PERFUME_AI_RECOMMEND_MODEL_DIGEST", "");
        }
        var source = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(PerfumeSemanticSearchConfiguration.class,
                PerfumeRecommendationConfiguration.class, PerfumeEmbeddingConfiguration.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("composeEnvironment", inputs)))
                .withPropertyValues("perfume.ai.search.enabled=false", "perfume.ai.recommend.enabled=false",
                        "perfume.ai.embedding.batch.enabled=false", "perfume.ai.embedding.auto.enabled=false")
                .withBean(DataSource.class, () -> source).withBean(RestClient.Builder.class, RestClient::builder)
                .withBean(ObjectMapper.class, ObjectMapper::new).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ApplicationRunner.class).doesNotHaveBean(PerfumeEmbeddingStore.class);
                    if (enabled) {
                        assertThat(context).hasSingleBean(PerfumeSemanticSearchService.class).hasSingleBean(PerfumeRecommendationService.class);
                        assertThat(context.getBean(PerfumeRecommendationOptions.class).requestTimeout().toMillis()).isEqualTo(420000);
                    } else {
                        assertThat(context).doesNotHaveBean(PerfumeSemanticSearchService.class).doesNotHaveBean(PerfumeRecommendationService.class);
                    }
                    verifyNoInteractions(source);
                });
    }

    @Test void missingComposeFlagsUseTheRealDisabledApplicationDefaults() throws Exception {
        var environment = environment(Map.of());
        assertThat(environment.getProperty("perfume.ai.search.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("perfume.ai.recommend.enabled", Boolean.class)).isFalse();
    }

    @ParameterizedTest @MethodSource("invalidInputs")
    void invalidComposeIdentityOrDependencyFailsBeforeContextAndAnyIo(Map<String, Object> inputs) throws Exception {
        var app = application(inputs);
        var initialized = new AtomicBoolean();
        app.addInitializers(context -> initialized.set(true));
        assertThatThrownBy(() -> app.run("--spring.main.banner-mode=off"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(initialized).isFalse();
    }

    @Test void composeEnabledListenersStillForceReadOnlySchemaSettingsBeforeContext() throws Exception {
        var app = application(enabled());
        var checked = new AtomicBoolean();
        app.addInitializers(context -> {
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
            assertThat(context.getEnvironment().getProperty("spring.jpa.show-sql")).isEqualTo("false");
            checked.set(true); throw new IllegalStateException("stopped-before-context");
        });
        assertThatThrownBy(() -> app.run("--spring.main.banner-mode=off",
                "--spring.jpa.hibernate.ddl-auto=create", "--spring.sql.init.mode=always"))
                .isInstanceOf(IllegalStateException.class).hasMessage("stopped-before-context");
        assertThat(checked).isTrue();
    }

    private static Stream<Map<String, Object>> invalidInputs() {
        var result = new java.util.ArrayList<Map<String, Object>>();
        for (var feature : new String[] {"SEARCH", "RECOMMEND"}) {
            for (var invalid : new String[] {"missing", "", "short", "sha256:" + "g".repeat(64)}) {
                var input = enabled();
                if (invalid.equals("missing")) input.remove("PERFUME_AI_" + feature + "_MODEL_DIGEST");
                else input.put("PERFUME_AI_" + feature + "_MODEL_DIGEST", invalid);
                result.add(input);
            }
        }
        var noSearch = enabled(); noSearch.put("PERFUME_AI_SEARCH_ENABLED", "false"); result.add(noSearch);
        return result.stream();
    }

    private static SpringApplication application(Map<String, Object> inputs) throws Exception {
        var app = new SpringApplication(Object.class);
        app.setEnvironment(environment(inputs));
        app.setLogStartupInfo(false);
        PerfumeRecommendationConfiguration.configureExecution(app);
        PerfumeSemanticSearchConfiguration.configureReadOnlyExecution(app);
        return app;
    }

    private static StandardEnvironment environment(Map<String, Object> inputs) throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, inputs));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        return environment;
    }

    private static Map<String, Object> enabled() {
        var inputs = new HashMap<String, Object>();
        inputs.put("PERFUME_AI_SEARCH_ENABLED", "true"); inputs.put("PERFUME_AI_SEARCH_MODEL_DIGEST", "a".repeat(64));
        inputs.put("PERFUME_AI_RECOMMEND_ENABLED", "true"); inputs.put("PERFUME_AI_RECOMMEND_MODEL_DIGEST", "sha256:" + "b".repeat(64));
        inputs.put("PERFUME_AI_SEARCH_MODEL", "qwen3-embedding:0.6b"); inputs.put("PERFUME_AI_RECOMMEND_MODEL", "qwen3:4b-instruct");
        inputs.put("PERFUME_AI_SEARCH_READ_TIMEOUT_MS", "60000"); inputs.put("PERFUME_AI_SEARCH_REQUEST_TIMEOUT_MS", "120000");
        inputs.put("PERFUME_AI_SEARCH_MAX_CONCURRENT", "1"); inputs.put("PERFUME_AI_RECOMMEND_MAX_CONCURRENT", "1");
        inputs.put("PERFUME_AI_RECOMMEND_READ_TIMEOUT_MS", "300000"); inputs.put("PERFUME_AI_RECOMMEND_GENERATION_TIMEOUT_MS", "300000");
        inputs.put("PERFUME_AI_RECOMMEND_REQUEST_TIMEOUT_MS", "420000");
        return inputs;
    }
}
