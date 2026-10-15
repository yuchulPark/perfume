package com.perfume.scentrev.preview;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.perfume.ai.embedding.EmbeddingBatchOptions;
import com.perfume.ai.embedding.PerfumeEmbeddingBatchRunner;
import com.perfume.scentrev.client.ScentRevMcpClientException.FailureType;
import com.perfume.scentrev.preview.ScentRevBrandPreviewResult.StopReason;
import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class ScentRevBrandPreviewRunnerTests {
    @Test
    void previewIsDisabledByDefaultWithoutRequiringAnyRepositoriesOrClients() {
        new ApplicationContextRunner().withUserConfiguration(ScentRevBrandPreviewRunner.class,
                ScentRevBrandPreviewService.class, ScentRevBrandPreviewLocalBrand.class).run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(ScentRevBrandPreviewRunner.class)
                            .doesNotHaveBean(ScentRevBrandPreviewService.class).doesNotHaveBean(ScentRevBrandPreviewLocalBrand.class);
                });
        var ordinary = new MockEnvironment().withProperty("spring.jpa.hibernate.ddl-auto", "update");
        var application = new SpringApplication(Object.class);
        ScentRevBrandPreviewRunner.prepare(application, ordinary, new String[0]);
        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
        assertThat(ordinary.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
        assertThat(ordinary.containsProperty("spring.datasource.hikari.read-only")).isFalse();
    }

    @Test
    void manualPreviewForcesNonWebValidationAndReadOnlyPoolWithoutRunningAnything() {
        var env = environment().withProperty("spring.jpa.hibernate.ddl-auto", "create-drop")
                .withProperty("spring.sql.init.mode", "always").withProperty("spring.datasource.hikari.read-only", "false");
        var application = new SpringApplication(Object.class);
        ScentRevBrandPreviewRunner.prepare(application, env, flag());
        assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
        assertThat(env.getProperty("spring.main.web-application-type")).isEqualTo("none");
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(env.getProperty("spring.sql.init.mode")).isEqualTo("never");
        assertThat(env.getProperty("spring.datasource.hikari.read-only")).isEqualTo("true");
        assertThat(env.getProperty("spring.jpa.show-sql")).isEqualTo("false");
    }

    @Test
    void inheritedActivationWithoutTheExplicitProgramArgumentIsRejected() {
        assertThatThrownBy(() -> ScentRevBrandPreviewRunner.prepare(new SpringApplication(Object.class), environment(), new String[0]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("program argument");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scentrev.brand-import", "perfume.ai.embedding.batch.enabled"})
    void mixedManualModesAreRejectedBeforeAnApplicationContextOrDatasourceCanBeCreated(String conflict) {
        var application = new SpringApplication(Object.class);
        // Same order as PerfumeApplication; Preview sees the original flags before other listeners can mask them.
        ScentRevBrandPreviewRunner.configureManualExecution(application);
        ScentRevSingleBrandImportRunner.configureManualExecution(application);
        PerfumeEmbeddingBatchRunner.configureManualExecution(application);
        var initialized = new AtomicBoolean();
        application.addInitializers(context -> initialized.set(true));
        assertThatThrownBy(() -> application.run("--scentrev.brand-preview=true", "--scentrev.brand-preview.brand-slug=creed",
                "--" + conflict + "=true", "--spring.main.banner-mode=off"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separately");
        assertThat(initialized).isFalse();
    }

    @Test
    void defaultAndExplicitBudgetsHaveIndependentBounds() {
        var defaults = ScentRevBrandPreviewOptions.from(environment());
        assertThat(defaults.maxPages()).isEqualTo(5); assertThat(defaults.apiCallBudget()).isEqualTo(5);
        var expanded = ScentRevBrandPreviewOptions.from(environment().withProperty("scentrev.brand-preview.max-pages", "100"));
        assertThat(expanded.maxPages()).isEqualTo(100); assertThat(expanded.apiCallBudget()).isEqualTo(100);
        var budget = ScentRevBrandPreviewOptions.from(environment().withProperty("scentrev.brand-preview.api-call-budget", "0"));
        assertThat(budget.apiCallBudget()).isZero();
        for (var property : List.of("max-pages=0", "max-pages=101", "max-pages=oops", "max-pages=1.5", "api-call-budget=-1",
                "api-call-budget=101", "api-call-budget=oops", "brand-slugs=creed")) {
            var pair = property.split("=", 2);
            assertThatThrownBy(() -> ScentRevBrandPreviewRunner.prepare(new SpringApplication(Object.class),
                    environment().withProperty("scentrev.brand-preview." + pair[0], pair[1]), flag()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "all", "*", "creed,memo-paris", "CREED", " creed", "creed ", "../creed"})
    void missingOrMultipleOrMalformedBrandSlugFailsBeforeStartup(String slug) {
        var env = environment();
        if (slug == null) { env = new MockEnvironment().withProperty("scentrev.brand-preview", "true"); }
        else { env.withProperty("scentrev.brand-preview.brand-slug", slug); }
        var selected = env;
        assertThatThrownBy(() -> ScentRevBrandPreviewRunner.prepare(new SpringApplication(Object.class), selected, flag()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedPreviewReturnsNormallyAndPrintsCandidatesAndEstimatesWithoutClaimingDailyQuota(CapturedOutput output) {
        var service = mock(ScentRevBrandPreviewService.class);
        when(service.preview(any())).thenReturn(result(44, 5, true, false, StopReason.COMPLETED, null));
        new ScentRevBrandPreviewRunner(service, environment()).run(new DefaultApplicationArguments(flag()));
        assertThat(output).contains("candidateCount = 44", "completed = true", "hasMore = false", "listApiCalls = 5",
                "estimatedFullImportCalls = 49", "referenceCallsAssumingAllObservedCandidatesNew = 53", "dailyRemaining = unknown",
                "NOT profile-verified", "Rerunning starts at offset 0");
        verify(service).preview(new ScentRevBrandPreviewOptions("creed", 5, 5)); verifyNoMoreInteractions(service);
    }

    @Test
    void aPageCapIsANormalPartialExitWithAnUnknownFullEstimate(CapturedOutput output) {
        var service = mock(ScentRevBrandPreviewService.class);
        when(service.preview(any())).thenReturn(result(50, 5, false, true, StopReason.MAX_PAGES, null));
        new ScentRevBrandPreviewRunner(service, environment()).run(new DefaultApplicationArguments(flag()));
        assertThat(output).contains("candidateCount = 50", "completed = false", "hasMore = true", "stopReason = MAX_PAGES",
                "estimatedFullImportCalls = unknown (partial preview)");
    }

    @Test
    void anApiFailurePrintsPartialCountsAndExitsWithAFixedFailureWithoutRetry(CapturedOutput output) {
        var service = mock(ScentRevBrandPreviewService.class);
        when(service.preview(any())).thenReturn(result(10, 1, false, true, StopReason.API_ERROR, FailureType.RATE_LIMIT));
        var runner = new ScentRevBrandPreviewRunner(service, environment());
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(flag())))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageContaining("API_ERROR");
        assertThat(output).contains("completed = false", "failureType = RATE_LIMIT", "estimatedFullImportCalls = unknown")
                .doesNotContain("SCENTREV_API_KEY=", "Authorization", "Bearer");
        verify(service).preview(any()); verifyNoMoreInteractions(service);
    }

    @Test
    void previewDoesNotEnableImportOrEmbeddingBeans() {
        var env = environment();
        ScentRevBrandPreviewRunner.prepare(new SpringApplication(Object.class), env, flag());
        assertThat(env.getProperty(ScentRevSingleBrandImportRunner.ENABLED_PROPERTY, Boolean.class, false)).isFalse();
        assertThat(env.getProperty(EmbeddingBatchOptions.ENABLED, Boolean.class, false)).isFalse();
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("scentrev.brand-preview", "true").withProperty("scentrev.brand-preview.brand-slug", "creed");
    }
    private static String[] flag() { return new String[] {"--scentrev.brand-preview=true"}; }
    private static ScentRevBrandPreviewResult result(int candidates, int pages, boolean complete, Boolean more, StopReason reason, FailureType type) {
        return new ScentRevBrandPreviewResult("Creed", "creed", 12, candidates, candidates, 0, 0,
                pages, pages + (type == null ? 0 : 1), pages * 10, complete, more, reason, type, null);
    }
}
