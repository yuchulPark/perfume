package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;

class AutoEmbeddingConfigurationTests {
    @TempDir Path directory;

    @Test void defaultCreatesNoAutomaticRunnerAndOptInCreatesOnlyOneRunnerWithoutIO() {
        new ApplicationContextRunner().withUserConfiguration(PerfumeEmbeddingConfiguration.class).run(context ->
                assertThat(context).doesNotHaveBean(AutoPerfumeEmbeddingRunner.class));
        var source = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(PerfumeEmbeddingConfiguration.class)
                .withPropertyValues(EmbeddingBatchOptions.ENABLED + "=true", AutoEmbeddingOptions.ENABLED + "=true",
                        EmbeddingBatchOptions.PREFIX + "model-digest=" + "a".repeat(64),
                        AutoEmbeddingOptions.PREFIX + "state-directory=" + directory)
                .withBean(DataSource.class, () -> source).withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(RestClient.Builder.class, RestClient::builder).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(AutoPerfumeEmbeddingRunner.class).doesNotHaveBean(PerfumeEmbeddingBatchRunner.class);
                    verifyNoInteractions(source);
                });
    }

    @Test void explicitAutoFlagAndPersistedCursorAreRequiredBeforeJdbcStartup() {
        assertThatThrownBy(() -> PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class),
                new MockEnvironment().withProperty(AutoEmbeddingOptions.ENABLED, "true"), new String[0]))
                .hasMessageContaining("batch execution mode");
        var env = environment();
        assertThatThrownBy(() -> PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class), env,
                new String[] {"--" + EmbeddingBatchOptions.ENABLED + "=true"})).hasMessageContaining("explicit");
        var flags = new String[] {"--" + EmbeddingBatchOptions.ENABLED + "=true", "--" + AutoEmbeddingOptions.ENABLED + "=true"};
        PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class), env, flags);
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThatThrownBy(() -> PerfumeEmbeddingBatchRunner.prepare(new SpringApplication(Object.class),
                environment().withProperty(EmbeddingBatchOptions.PREFIX + "batch.after-id", "1"), flags)).hasMessageContaining("persisted");
    }

    @Test void invalidRuntimePathAndMalformedSettingsFailClosed() {
        for (var value : List.of("0", "-1", "86401", "oops")) {
            assertThatThrownBy(() -> AutoEmbeddingOptions.from(environment()
                    .withProperty(AutoEmbeddingOptions.PREFIX + "max-runtime-seconds", value))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new AutoEmbeddingOptions(Path.of("relative"), Duration.ofHours(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutoEmbeddingOptions.from(new MockEnvironment())).isInstanceOf(IllegalArgumentException.class);
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty(EmbeddingBatchOptions.ENABLED, "true")
                .withProperty(AutoEmbeddingOptions.ENABLED, "true")
                .withProperty(EmbeddingBatchOptions.PREFIX + "model-digest", "a".repeat(64))
                .withProperty(AutoEmbeddingOptions.PREFIX + "state-directory", directory.toString());
    }
}
