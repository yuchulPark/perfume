package com.perfume.scentrev.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** No generated toString: configuration includes a credential. */
@ConfigurationProperties(prefix = "scentrev")
public class ScentRevClientProperties {

    private String baseUrl = "https://api.scentrev.com";
    private String apiKey = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
