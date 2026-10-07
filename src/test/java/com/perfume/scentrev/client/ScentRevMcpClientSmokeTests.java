package com.perfume.scentrev.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientProperties;

/** One read-only profile call. Both gates are required; no Spring context or persistence. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = "(?s).*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.mcp-live-test", matches = "true")
class ScentRevMcpClientSmokeTests {

    @Test
    void retrievesKnownAventusPhase1ProfileWithoutInvokingPersistence() {
        ScentRevClientProperties properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));

        try (ScentRevMcpClient client = new ScentRevMcpClient(properties, new ObjectMapper())) {
            var profile = client.getFragranceProfile("creed-aventus");

            assertThat(profile.identity()).isNotNull();
            assertThat(profile.identity().name()).isEqualTo("Aventus");
            assertThat(profile.identity().brandSlug()).isEqualTo("creed");
            assertThat(profile.identity().fragranceSlug()).isEqualTo("creed-aventus");
            assertThat(profile.perfumers()).isNotNull();
            assertThat(profile.notePyramid()).isNotNull();
            assertThat(profile.accords()).isNotNull();
        }
    }
}
