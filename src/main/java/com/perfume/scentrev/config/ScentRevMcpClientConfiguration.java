package com.perfume.scentrev.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevMcpClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScentRevClientProperties.class)
public class ScentRevMcpClientConfiguration {

    @Bean(destroyMethod = "close")
    public ScentRevMcpClient scentrevMcpClient(ScentRevClientProperties properties, ObjectMapper objectMapper) {
        return new ScentRevMcpClient(properties, objectMapper);
    }
}
