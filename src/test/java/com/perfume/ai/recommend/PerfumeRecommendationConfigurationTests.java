package com.perfume.ai.recommend;

import static com.perfume.ai.recommend.RecommendationTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.ai.embedding.*;
import com.perfume.ai.search.*;
import com.perfume.scentrev.preview.ScentRevBrandPreviewRunner;
import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

class PerfumeRecommendationConfigurationTests {
    @Test void disabledNeedsNoDatasourceClientsOrModelSettingsAndStartsNoManualJob() {
        contexts().withPropertyValues("perfume.ai.recommend.model-digest=invalid-unused").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(PerfumeRecommendationService.class).doesNotHaveBean(OllamaRecommendationClient.class)
                    .doesNotHaveBean(JdbcRecommendationEvidenceRepository.class).doesNotHaveBean(ApplicationRunner.class).doesNotHaveBean(PerfumeSemanticSearchService.class);
        });
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void searchAndRecommendationFlagsRemainIndependentAndStartupMakesNoModelOrEvidenceCalls(boolean recommend) {
        var source = mock(DataSource.class);
        contexts().withPropertyValues("perfume.ai.search.enabled=true", "perfume.ai.search.model-digest=" + "a".repeat(64),
                "perfume.ai.recommend.enabled=" + recommend, "perfume.ai.recommend.model-digest=" + "b".repeat(64))
                .withBean(DataSource.class, () -> source).withBean(RestClient.Builder.class, RestClient::builder).withBean(ObjectMapper.class, ObjectMapper::new).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(PerfumeSemanticSearchService.class).doesNotHaveBean(ApplicationRunner.class).doesNotHaveBean(PerfumeEmbeddingStore.class);
                    if (recommend) {
                        assertThat(context).hasSingleBean(PerfumeRecommendationService.class).hasSingleBean(OllamaRecommendationClient.class);
                        var options = context.getBean(PerfumeRecommendationOptions.class); assertThat(options.modelDigest()).isNotEqualTo(context.getBean(PerfumeSemanticSearchOptions.class).model().digest());
                        assertThat(options.readTimeout().toMillis()).isEqualTo(180000); assertThat(options.generationTimeout().toMillis()).isEqualTo(300000);
                        assertThat(options.requestTimeout().toMillis()).isEqualTo(420000); assertThat(options.maxConcurrent()).isEqualTo(1);
                        assertThat(options.maxOutputTokens()).isEqualTo(256); assertThat(options.maxEvidenceCandidates()).isEqualTo(1);
                        assertThat(options.inputBudgetBytes()).isEqualTo(2560); assertThat(options.optimizationMode()).isEqualTo("compact");
                    } else { assertThat(context).doesNotHaveBean(OllamaRecommendationClient.class).doesNotHaveBean(JdbcRecommendationEvidenceRepository.class); }
                    var search = context.getBean(PerfumeSemanticSearchOptions.class);
                    assertThat(search.readTimeout().toMillis()).isEqualTo(30000); assertThat(search.requestTimeout().toMillis()).isEqualTo(45000);
                    verifyNoInteractions(source);
                });
    }
    @ParameterizedTest @ValueSource(strings = {"search-disabled", "scentrev.brand-import", "scentrev.brand-preview", "perfume.ai.embedding.batch.enabled"})
    void missingSearchOrMixedManualModesAreRejectedBeforeContextCreation(String badMode) {
        var app = application(); var initialized = new AtomicBoolean(); app.addInitializers(context -> initialized.set(true));
        var args = new java.util.ArrayList<>(List.of("--perfume.ai.recommend.enabled=true", "--perfume.ai.search.enabled=" + !badMode.equals("search-disabled"),
                "--perfume.ai.search.model-digest=" + "a".repeat(64), "--perfume.ai.recommend.model-digest=" + "b".repeat(64), "--spring.main.banner-mode=off"));
        if (!badMode.equals("search-disabled")) { args.add("--" + badMode + "=true"); }
        assertThatThrownBy(() -> app.run(args.toArray(String[]::new))).isInstanceOf(IllegalArgumentException.class);
        assertThat(initialized).isFalse();
    }
    @Test void existingSearchListenerStillForcesValidateAndNeverInitializesSqlBeforeContext() {
        var app = application(); var checked = new AtomicBoolean();
        app.addInitializers(context -> {
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
            assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
            checked.set(true); throw new IllegalStateException("stopped-before-context");
        });
        assertThatThrownBy(() -> app.run("--perfume.ai.recommend.enabled=true", "--perfume.ai.search.enabled=true", "--perfume.ai.search.model-digest=" + "a".repeat(64),
                "--perfume.ai.recommend.model-digest=" + "b".repeat(64), "--spring.jpa.hibernate.ddl-auto=create", "--spring.sql.init.mode=always", "--spring.main.banner-mode=off"))
                .isInstanceOf(IllegalStateException.class).hasMessage("stopped-before-context");
        assertThat(checked).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"model-digest=short", "model=qwen3-embedding:0.6b", "connect-timeout-ms=0", "connect-timeout-ms=10001", "read-timeout-ms=300001",
            "generation-timeout-ms=0", "generation-timeout-ms=300001", "request-timeout-ms=600001", "max-concurrent=0", "max-concurrent=3", "db-timeout-seconds=11",
            "max-output-tokens=127", "max-output-tokens=769", "max-input-bytes=1023", "max-input-bytes=2817", "max-evidence-candidates=0", "max-evidence-candidates=3",
            "temperature=NaN", "temperature=0.6", "base-url=http://user:private@ollama:11434", "base-url=http://ollama:11434/api/chat"})
    void generationSettingsHaveFiniteLimitsAndAnIndependentFullDigest(String bad) {
        var pair = bad.split("=", 2);
        assertThatThrownBy(() -> PerfumeRecommendationOptions.from(environment().withProperty(PerfumeRecommendationOptions.PREFIX + pair[0], pair[1])))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingLlmDigestDoesNotFallBackToTheEmbeddingDigest() {
        assertThatThrownBy(() -> PerfumeRecommendationOptions.from(new MockEnvironment().withProperty("perfume.ai.search.enabled", "true")
                .withProperty("perfume.ai.search.model-digest", "a".repeat(64)))).isInstanceOf(IllegalArgumentException.class);
    }
    private static ApplicationContextRunner contexts() {
        return new ApplicationContextRunner().withUserConfiguration(PerfumeRecommendationConfiguration.class, PerfumeSemanticSearchConfiguration.class,
                PerfumeEmbeddingConfiguration.class, ScentRevBrandPreviewRunner.class, ScentRevSingleBrandImportRunner.class);
    }
    private static SpringApplication application() {
        var app = new SpringApplication(Object.class); PerfumeRecommendationConfiguration.configureExecution(app);
        PerfumeSemanticSearchConfiguration.configureReadOnlyExecution(app); ScentRevBrandPreviewRunner.configureManualExecution(app);
        ScentRevSingleBrandImportRunner.configureManualExecution(app); PerfumeEmbeddingBatchRunner.configureManualExecution(app); return app;
    }
    private static MockEnvironment environment() { return new MockEnvironment().withProperty("perfume.ai.search.enabled", "true")
            .withProperty("perfume.ai.search.model-digest", "a".repeat(64)).withProperty("perfume.ai.recommend.model-digest", "b".repeat(64)); }
}
