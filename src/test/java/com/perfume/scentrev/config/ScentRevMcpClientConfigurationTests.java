package com.perfume.scentrev.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;
import com.perfume.scentrev.client.ScentRevMcpClientException;

/** Focused configuration only; no Spring Boot application or database. */
class ScentRevMcpClientConfigurationTests {

    @Test
    void missingKeyAllowsStartupAndSpringClosesClientAtShutdown() {
        AtomicReference<ScentRevMcpClient> managedClient = new AtomicReference<>();
        new ApplicationContextRunner().withUserConfiguration(ScentRevMcpClientConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("scentrev.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScentRevMcpClient.class);
                    managedClient.set(context.getBean(ScentRevMcpClient.class));
                    assertThatThrownBy(() -> managedClient.get().getFragranceProfile("creed-aventus"))
                            .isInstanceOf(ScentRevMcpClientException.class)
                            .hasMessageContaining("SCENTREV_API_KEY");
                });

        assertThatThrownBy(() -> managedClient.get().getFragranceProfile("creed-aventus"))
                .isInstanceOf(ScentRevMcpClientException.class).hasMessageContaining("client is closed");
    }
}
