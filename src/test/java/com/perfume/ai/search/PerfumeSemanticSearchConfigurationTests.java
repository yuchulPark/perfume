package com.perfume.ai.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import com.perfume.ai.embedding.*;
import com.perfume.scentrev.preview.*;
import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class PerfumeSemanticSearchConfigurationTests {
    @Test void disabledDefaultNeedsNoDatasourceOrModelsAndDoesNotCreateAnySearchOrBatchResources() {
        new ApplicationContextRunner().withUserConfiguration(PerfumeSemanticSearchConfiguration.class, PerfumeEmbeddingConfiguration.class,
                ScentRevBrandPreviewRunner.class, ScentRevSingleBrandImportRunner.class)
                .withPropertyValues("perfume.ai.search.model-digest=invalid-unused-digest").run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(PerfumeSemanticSearchService.class).doesNotHaveBean(OllamaQueryEmbeddingAdapter.class)
                            .doesNotHaveBean(JdbcPerfumeSemanticSearchRepository.class).doesNotHaveBean(ApplicationRunner.class);
                });
    }
    @Test void enablingSearchCreatesOnlySearchBeansWithoutCallingOllamaOrCheckingAiTablesAtStartup() {
        var source = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(PerfumeSemanticSearchConfiguration.class, PerfumeEmbeddingConfiguration.class,
                ScentRevBrandPreviewRunner.class, ScentRevSingleBrandImportRunner.class)
                .withPropertyValues("perfume.ai.search.enabled=true", "perfume.ai.search.model-digest=" + "a".repeat(64))
                .withBean(DataSource.class, () -> source).withBean(RestClient.Builder.class, RestClient::builder).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PerfumeSemanticSearchService.class).hasSingleBean(OllamaQueryEmbeddingAdapter.class)
                            .doesNotHaveBean(PerfumeEmbeddingBatchRunner.class).doesNotHaveBean(PerfumeEmbeddingStore.class).doesNotHaveBean(ApplicationRunner.class);
                    var options = context.getBean(PerfumeSemanticSearchOptions.class);
                    assertThat(options.readTimeout().toMillis()).isEqualTo(30000);
                    assertThat(options.requestTimeout().toMillis()).isEqualTo(45000);
                    assertThat(options.maxConcurrent()).isEqualTo(1); verifyNoInteractions(source);
                });
    }
    @Test void startupForcesValidateAndNoSqlInitializationOnlyWhenSearchIsEnabled() {
        var ordinary = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        PerfumeSemanticSearchConfiguration.prepare(ordinary); assertThat(ordinary.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        var enabled = environment().withProperty("spring.jpa.hibernate.ddl-auto", "create").withProperty("spring.sql.init.mode", "always");
        PerfumeSemanticSearchConfiguration.prepare(enabled);
        assertThat(enabled.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(enabled.getProperty("spring.sql.init.mode")).isEqualTo("never");
    }
    @ParameterizedTest @ValueSource(strings = {"scentrev.brand-import", "scentrev.brand-preview", "perfume.ai.embedding.batch.enabled"})
    void mixedModesAreRejectedBeforeContextAndManualJobsStart(String mode) {
        var app = new SpringApplication(Object.class); PerfumeSemanticSearchConfiguration.configureReadOnlyExecution(app);
        ScentRevBrandPreviewRunner.configureManualExecution(app); ScentRevSingleBrandImportRunner.configureManualExecution(app); PerfumeEmbeddingBatchRunner.configureManualExecution(app);
        var initialized = new AtomicBoolean(); app.addInitializers(context -> initialized.set(true));
        assertThatThrownBy(() -> app.run("--perfume.ai.search.enabled=true", "--perfume.ai.search.model-digest=" + "a".repeat(64),
                "--" + mode + "=true", "--spring.main.banner-mode=off")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separately");
        assertThat(initialized).isFalse();
    }
    @Test void fullModelIdentityIsMandatoryAndLimitsCannotBecomeUnbounded() {
        for (var bad : List.of("model-digest=short", "model-digest=latest", "text-version=perfume-v0", "connect-timeout-ms=0",
                "connect-timeout-ms=10001", "read-timeout-ms=60001", "request-timeout-ms=120001", "max-concurrent=0", "max-concurrent=3",
                "db-timeout-seconds=0", "db-timeout-seconds=11", "base-url=http://user:private@ollama:11434", "base-url=http://ollama:11434/api/embed")) {
            var pair = bad.split("=", 2);
            assertThatThrownBy(() -> PerfumeSemanticSearchOptions.from(environment().withProperty(PerfumeSemanticSearchOptions.PREFIX + pair[0], pair[1])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PerfumeSemanticSearchOptions.from(new MockEnvironment())).isInstanceOf(IllegalArgumentException.class);
    }
    private static MockEnvironment environment() { return new MockEnvironment().withProperty("perfume.ai.search.enabled", "true").withProperty("perfume.ai.search.model-digest", "a".repeat(64)); }
}
