package com.perfume.scentrev.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.StandardEnvironment;
import java.util.Map;

@ExtendWith(OutputCaptureExtension.class)
class ScentRevSkipExistingRunnerTests {
    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "True"})
    void explicitJvmStyleOptionEnablesSkippingAndKeepsExistingDelayAndSlug(String value) {
        var importer = mock(ScentRevSingleBrandImportService.class);
        var env = environment().withProperty(ScentRevSingleBrandImportRunner.SKIP_EXISTING_PROPERTY, value);
        when(importer.importBrand(new ScentRevSingleBrandImportOptions("creed", 250, true))).thenReturn(result(true));
        new ScentRevSingleBrandImportRunner(importer, env).run(new DefaultApplicationArguments());
        verify(importer).importBrand(new ScentRevSingleBrandImportOptions("creed", 250, true)); verifyNoMoreInteractions(importer);
    }

    @Test
    void absentAndExplicitFalseRemainTheDefaultWithoutAnyNewExecutionMode() {
        assertThat(ScentRevSingleBrandImportOptions.parse("creed", null).skipExisting()).isFalse();
        assertThat(ScentRevSingleBrandImportOptions.parse("creed", "250", "false").skipExisting()).isFalse();
        assertThat(new ScentRevSingleBrandImportOptions("creed", 250).skipExisting()).isFalse();
        var importer = mock(ScentRevSingleBrandImportService.class);
        when(importer.importBrand(new ScentRevSingleBrandImportOptions("creed", 250, false))).thenReturn(result(false));
        new ScentRevSingleBrandImportRunner(importer, environment()).run(new DefaultApplicationArguments());
        new ScentRevSingleBrandImportRunner(importer, environment().withProperty(ScentRevSingleBrandImportRunner.SKIP_EXISTING_PROPERTY, "FALSE"))
                .run(new DefaultApplicationArguments());
        verify(importer, times(2)).importBrand(new ScentRevSingleBrandImportOptions("creed", 250, false));
    }

    @Test
    void aSystemPropertiesSourceSupportsTheUsersExistingJvmLaunchStyle() {
        var env = environment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of("scentrev.brand-import", "TRUE", "scentrev.brand-import.brand-slug", "penhaligon-s",
                        "scentrev.brand-import.delay-ms", "250", "scentrev.brand-import.skip-existing", "true")));
        var importer = mock(ScentRevSingleBrandImportService.class);
        var expected = new ScentRevSingleBrandImportOptions("penhaligon-s", 250, true);
        when(importer.importBrand(expected)).thenReturn(result(true));
        new ScentRevSingleBrandImportRunner(importer, env).run(new DefaultApplicationArguments());
        verify(importer).importBrand(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"yes", "1", "tru", " true "})
    void invalidBooleanIsRejectedAndNullKeepsTheDefault(String value) {
        if (value == null) { assertThat(ScentRevSingleBrandImportOptions.parse("creed", null, null).skipExisting()).isFalse(); }
        else { assertThatThrownBy(() -> ScentRevSingleBrandImportOptions.parse("creed", "250", value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("true or false"); }
    }

    @Test
    void finalResultAddsSeparateCountsAndPreservesTheExistingFields(CapturedOutput output) {
        var importer = mock(ScentRevSingleBrandImportService.class);
        when(importer.importBrand(new ScentRevSingleBrandImportOptions("creed", 250, true))).thenReturn(result(true));
        new ScentRevSingleBrandImportRunner(importer, environment().withProperty(ScentRevSingleBrandImportRunner.SKIP_EXISTING_PROPERTY, "true"))
                .run(new DefaultApplicationArguments());
        assertThat(output).contains("FINAL RESULT", "existingSkipped            = 60", "newCandidates              = 10",
                "skipExistingEnabled        = true", "profilesFetched            = 10", "perfumesInserted           = 10",
                "perfumesUpdated            = 0", "totalMcpCalls              = 17", "completed                  = true");
    }

    private static MockEnvironment environment() {
        return new MockEnvironment().withProperty("scentrev.brand-import.brand-slug", "creed").withProperty("scentrev.brand-import.delay-ms", "250");
    }
    private static ScentRevSingleBrandImportResult result(boolean enabled) {
        return new ScentRevSingleBrandImportResult("Creed", "Creed", "creed", 7, 1, 70, 0, 70, null,
                10, 0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 17, true, null, null, null, enabled ? 60 : 0, enabled ? 10 : null, enabled);
    }
}
