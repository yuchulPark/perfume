package com.perfume.scentrev.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevClient;

class ScentRevClientConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ScentRevClientConfiguration.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void bindsFocusedClientPropertiesAndCreatesClientWithoutMakingRequest() {
        contextRunner.withPropertyValues("scentrev.base-url=https://provider.example.test", "scentrev.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScentRevClient.class);
                    ScentRevClientProperties properties = context.getBean(ScentRevClientProperties.class);
                    assertThat(properties.getBaseUrl()).isEqualTo("https://provider.example.test");
                    assertThat(properties.getApiKey()).isEqualTo("test-key");
                    assertThat(properties.toString()).doesNotContain("test-key");
                });
    }

    @Test
    void missingKeyDoesNotPreventClientConfigurationFromStarting() {
        contextRunner.withPropertyValues("scentrev.api-key=").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ScentRevClient.class);
            ScentRevClientProperties properties = context.getBean(ScentRevClientProperties.class);
            assertThat(properties.getBaseUrl()).isEqualTo("https://api.scentrev.com");
            assertThat(properties.getApiKey()).isEmpty();
        });
    }
}
