package com.perfume.scentrev.service;

import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.stereotype.Component;

/** Production manual command: exactly one explicitly selected brand, with no JUnit execution path. */
@Component
@ConditionalOnProperty(name = "scentrev.brand-import", havingValue = "true")
public class ScentRevSingleBrandImportRunner implements ApplicationRunner {
    public static final String ENABLED_PROPERTY = "scentrev.brand-import";
    public static final String SLUG_PROPERTY = "scentrev.brand-import.brand-slug";
    public static final String DELAY_PROPERTY = "scentrev.brand-import.delay-ms";
    private final ScentRevSingleBrandImportService importer;
    private final Environment environment;

    public ScentRevSingleBrandImportRunner(ScentRevSingleBrandImportService importer, Environment environment) {
        this.importer = importer;
        this.environment = environment;
    }

    /** Validate flags before datasource/context startup; manual mode validates existing schema and has no web server. */
    public static void configureManualExecution(SpringApplication application) {
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
            var environment = event.getEnvironment();
            if (environment.getProperty(ENABLED_PROPERTY, Boolean.class, false)) {
                options(environment); // Missing/malformed slug, plural property or delay fails before DB access.
                event.getSpringApplication().setWebApplicationType(WebApplicationType.NONE);
                environment.getPropertySources().addFirst(new MapPropertySource("singleBrandImportExecution", Map.of(
                        "spring.main.web-application-type", "none",
                        "spring.jpa.hibernate.ddl-auto", "validate",
                        "spring.sql.init.mode", "never",
                        "spring.jpa.show-sql", "false")));
            }
        });
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try {
            printResult(importer.importBrand(options(environment)));
        } catch (ScentRevSingleBrandImportException exception) {
            System.err.println(exception.getMessage());
            printResult(exception.getResult());
            throw exception; // Clear failure exit; no retry or subsequent request.
        }
    }

    private static ScentRevSingleBrandImportOptions options(Environment environment) {
        if (environment.containsProperty("scentrev.brand-import.brand-slugs")) {
            throw new IllegalArgumentException("scentrev.brand-import.brand-slugs is no longer supported; use brand-slug for exactly one verified brand.");
        }
        return ScentRevSingleBrandImportOptions.parse(environment.getProperty(SLUG_PROPERTY),
                environment.getProperty(DELAY_PROPERTY));
    }

    private static void printResult(ScentRevSingleBrandImportResult result) {
        System.err.printf("%n==================================================%nFINAL RESULT%n"
                        + "==================================================%n"
                        + "brandName                  = %s%nproviderBrandName          = %s%nbrandSlug                  = %s%n"
                        + "discoveryPages             = %d%npagesCommitted             = %d%nproviderReportedTotal      = %s%n"
                        + "rawResults                 = %d%nduplicateDiscoveryCount    = %d%nuniqueFragrancesDiscovered = %d%n"
                        + "profilesFetched            = %d%nskippedForeignBrand        = %d%nperfumesInserted           = %d%nperfumesUpdated            = %d%n"
                        + "perfumesUnchanged          = %d%nperfumesProcessed          = %d%nperfumersProcessed         = %d%n"
                        + "notesProcessed             = %d%naccordsProcessed           = %d%nperfumePerfumerLinks       = %d%n"
                        + "perfumeNoteLinks           = %d%nperfumeAccordLinks         = %d%ntotalMcpCalls              = %d%n"
                        + "completed                  = %s%n==================================================%n",
                result.brandName(), result.providerBrandName(), result.brandSlug(), result.discoveryPages(), result.pagesCommitted(),
                result.providerReportedTotal() == null ? "not supplied (total_returned is per page)" : result.providerReportedTotal(),
                result.rawResults(), result.duplicateDiscoveryCount(), result.uniqueFragrancesDiscovered(), result.profilesFetched(), result.skippedForeignBrand(),
                result.perfumesInserted(), result.perfumesUpdated(), result.perfumesUnchanged(), result.perfumesProcessed(),
                result.perfumersProcessed(), result.notesProcessed(), result.accordsProcessed(), result.perfumePerfumerLinks(),
                result.perfumeNoteLinks(), result.perfumeAccordLinks(), result.totalMcpCalls(), result.completed());
        if (!result.completed()) {
            System.err.printf("stoppedStage = %s%nstoppedOffset = %s%nstoppedFragranceSlug = %s%n",
                    result.stoppedStage(), result.stoppedOffset(), result.stoppedFragranceSlug());
        }
    }
}
