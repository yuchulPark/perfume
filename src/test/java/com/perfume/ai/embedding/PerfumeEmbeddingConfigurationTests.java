package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

class PerfumeEmbeddingConfigurationTests {
    @Test
    void disabledByDefaultWithoutDatasourceOllamaOrAiSchema() {
        new ApplicationContextRunner().withUserConfiguration(PerfumeEmbeddingConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PerfumeEmbeddingStore.class)
                    .doesNotHaveBean(PerfumeEmbeddingClient.class).doesNotHaveBean(PerfumeEmbeddingBatchRunner.class);
        });
    }

    @Test
    void enablingConfigurationDoesNotContactOllamaOrOpenJdbcBeforeTheRunner() {
        var source = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(PerfumeEmbeddingConfiguration.class)
                .withPropertyValues(EmbeddingBatchOptions.ENABLED + "=true", EmbeddingBatchOptions.PREFIX + "model-digest=" + "a".repeat(64))
                .withBean(DataSource.class, () -> source).withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestClient.Builder.class, RestClient::builder).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PerfumeEmbeddingBatchRunner.class);
                    assertThat(context.getBean(EmbeddingBatchOptions.class).baseUrl().toString()).isEqualTo("http://ollama:11434");
                    verifyNoInteractions(source);
                });
    }

    @Test
    void manualListenerForcesNonWebValidateOnlyEvenIfCallerRequestsUpdate() {
        var env = environment().withProperty("spring.jpa.hibernate.ddl-auto", "update").withProperty("spring.sql.init.mode", "always");
        var app = new SpringApplication(Object.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        PerfumeEmbeddingBatchRunner.prepare(app, env, explicitFlag());
        assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(env.getProperty("scentrev.brand-import")).isEqualTo("false");
    }

    @Test
    void environmentActivationAndSimultaneousImportAreRejectedBeforeStartup() {
        assertThatThrownBy(() -> PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class), environment(), new String[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("explicit");
        assertThatThrownBy(() -> PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class),
                environment().withProperty("scentrev.brand-import", "true"), explicitFlag()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separately");
        var ordinary = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class), ordinary, new String[0]);
        assertThat(ordinary.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
    }

    @Test
    void invalidModelIdentityAndBatchBoundsFailBeforeStartup() {
        for (var value : List.of("", "latest", "abc", "sha256:" + "a".repeat(63))) {
            assertThatThrownBy(() -> EmbeddingBatchOptions.from(environment().withProperty(EmbeddingBatchOptions.PREFIX + "model-digest", value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (var property : List.of("batch.page-size=0", "batch.page-size=101", "batch.max-items=0", "batch.max-items=10001",
                "batch.after-id=-1", "batch.delay-ms=-1", "read-timeout-ms=300001", "connect-timeout-ms=30001",
                "base-url=http://user:password@ollama:11434", "base-url=http://ollama:11434/api/embed")) {
            var pair = property.split("=", 2);
            assertThatThrownBy(() -> EmbeddingBatchOptions.from(environment().withProperty(EmbeddingBatchOptions.PREFIX + pair[0], pair[1])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void readTimeoutDefaultsToFiveMinutesAndCanBeExplicitlyReducedWithinTheExistingLimit() {
        assertThat(EmbeddingBatchOptions.from(environment()).readTimeout()).isEqualTo(Duration.ofMillis(300000));
        for (long milliseconds : List.of(1L, 180000L, 300000L)) {
            assertThat(EmbeddingBatchOptions.from(environment().withProperty(EmbeddingBatchOptions.PREFIX + "read-timeout-ms",
                    Long.toString(milliseconds))).readTimeout()).isEqualTo(Duration.ofMillis(milliseconds));
        }
        for (var value : List.of("0", "300001")) {
            assertThatThrownBy(() -> EmbeddingBatchOptions.from(environment()
                    .withProperty(EmbeddingBatchOptions.PREFIX + "read-timeout-ms", value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty(EmbeddingBatchOptions.ENABLED, "true")
                .withProperty(EmbeddingBatchOptions.PREFIX + "model-digest", "a".repeat(64));
    }
    private String[] explicitFlag() { return new String[] {"--" + EmbeddingBatchOptions.ENABLED + "=true"}; }
}
