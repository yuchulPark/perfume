package com.perfume.scentrev.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.config.ScentRevClientConfiguration;
import com.perfume.scentrev.config.ScentRevClientProperties;
import com.perfume.scentrev.dto.ScentRevFragranceProfileResponse;

/** One real read-only call, requiring both SCENTREV_API_KEY and -Dscentrev.live-test=true. */
@EnabledIfEnvironmentVariable(named = "SCENTREV_API_KEY", matches = ".*\\S.*")
@EnabledIfSystemProperty(named = "scentrev.live-test", matches = "true")
class ScentRevClientSmokeTests {

    @Test
    void retrievesKnownAventusProfileWithoutInvokingPersistence() {
        String publicId = "312329ca-0ad9-4a79-a586-2a4b815c18f7";
        ScentRevClientProperties properties = new ScentRevClientProperties();
        properties.setApiKey(System.getenv("SCENTREV_API_KEY"));
        ScentRevClient client = new ScentRevClientConfiguration()
                .scentrevClient(RestClient.builder(), properties, new ObjectMapper());

        ScentRevFragranceProfileResponse profile = client.getFragranceProfile(publicId);

        assertThat(profile.identity().name()).isEqualTo("Aventus");
        assertThat(profile.identity().brandSlug()).isEqualTo("creed");
        assertThat(profile.identity().publicId()).isEqualTo(publicId);
    }
}
