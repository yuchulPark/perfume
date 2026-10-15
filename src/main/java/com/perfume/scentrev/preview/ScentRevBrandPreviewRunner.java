package com.perfume.scentrev.preview;

import java.util.Arrays;
import java.util.Map;
import com.perfume.ai.embedding.EmbeddingBatchOptions;
import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

/** Explicit manual CLI only; mixed-mode rejection happens before JDBC/context startup. */
@Component
@ConditionalOnProperty(name = ScentRevBrandPreviewOptions.PREFIX, havingValue = "true")
public class ScentRevBrandPreviewRunner implements ApplicationRunner {
    public static final String ENABLED_PROPERTY = ScentRevBrandPreviewOptions.PREFIX;
    private final ScentRevBrandPreviewService service;
    private final Environment environment;

    public ScentRevBrandPreviewRunner(ScentRevBrandPreviewService service, Environment environment) {
        this.service = service; this.environment = environment;
    }

    public static void configureManualExecution(SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                prepare(event.getSpringApplication(), event.getEnvironment(), event.getArgs()));
    }

    static void prepare(SpringApplication application, ConfigurableEnvironment environment, String[] args) {
        if (!environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) { return; }
        if (!Arrays.asList(args).contains("--" + ENABLED_PROPERTY + "=true")) {
            throw new IllegalArgumentException("Preview requires the explicit --scentrev.brand-preview=true program argument.");
        }
        if (environment.getProperty(ScentRevSingleBrandImportRunner.ENABLED_PROPERTY, Boolean.class, false)
                || environment.getProperty(EmbeddingBatchOptions.ENABLED, Boolean.class, false)) {
            throw new IllegalArgumentException("Run Preview separately from Import and the embedding batch.");
        }
        ScentRevBrandPreviewOptions.from(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        environment.getPropertySources().addFirst(new MapPropertySource("scentrevBrandPreviewExecution", Map.of(
                "spring.main.web-application-type", "none", "spring.jpa.hibernate.ddl-auto", "validate",
                "spring.sql.init.mode", "never", "spring.jpa.show-sql", "false", "spring.datasource.hikari.read-only", "true")));
    }

    @Override
    public void run(ApplicationArguments arguments) {
        var options = ScentRevBrandPreviewOptions.from(environment);
        var result = service.preview(options);
        System.err.printf("%nSCENTREV BRAND PREVIEW (list candidates; brand membership NOT profile-verified)%n"
                        + "brandName = %s%nbrandSlug = %s%ndbPerfumeCount = %d%nrawCandidateCount = %d%n"
                        + "candidateCount = %d%nduplicateCount = %d%nforeignBrandCandidateRows = %d%n"
                        + "pagesFetched = %d%nlistApiCalls = %d%nmaxPages = %d%napiCallBudget = %d%n"
                        + "completed = %s%nhasMore = %s%nstopReason = %s%nnextOffset = %d%n"
                        + "failureType = %s%nretryAfterMs = %s%ndailyRemaining = unknown (no verified response field)%n"
                        + "basicImportCallsForObservedCandidates = %d (list pages + profile candidates; estimate)%n"
                        + "estimatedFullImportCalls = %s%nreferenceCallsAssumingAllObservedCandidatesNew = %d (71/60, about 1.18; example only)%n"
                        + "Estimates are not guaranteed usage. DB overlap, foreign candidates and optional wear calls can change actual use.%n"
                        + "Rerunning starts at offset 0 and consumes list calls again. No profiles, DB writes or automatic retries.%n",
                result.brandName(), result.brandSlug(), result.dbPerfumeCount(), result.rawCandidateCount(), result.candidateCount(),
                result.duplicateCount(), result.foreignBrandCandidateRows(), result.pagesFetched(), result.listApiCalls(), options.maxPages(), options.apiCallBudget(),
                result.completed(), result.hasMore() == null ? "unknown" : result.hasMore(), result.stopReason(), result.nextOffset(),
                result.failureType() == null ? "none" : result.failureType(), result.retryAfterMs() == null ? "unknown" : result.retryAfterMs(),
                result.basicImportCallsForObservedCandidates(), result.estimatedFullImportCalls() == null ? "unknown (partial preview)" : result.estimatedFullImportCalls(),
                result.referenceCallsAssumingAllObservedCandidatesNew());
        if (result.failed()) { throw new IllegalStateException("ScentRev Preview stopped: " + result.stopReason() + "; no automatic retry."); }
    }
}
