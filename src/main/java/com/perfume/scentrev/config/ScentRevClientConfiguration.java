package com.perfume.scentrev.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.perfume.scentrev.client.ScentRevClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScentRevClientProperties.class)
public class ScentRevClientConfiguration {

    @Bean
    public ScentRevClient scentrevClient(RestClient.Builder builder, ScentRevClientProperties properties,
                                       ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        RestClient restClient = builder.baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory).build();
        return new ScentRevClient(restClient, properties, objectMapper);
    }
}
