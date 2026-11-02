package com.perfume.search;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class ElasticsearchConfiguration {
    @Bean
    CatalogSearch catalogSearch(Environment env, ObjectMapper mapper) {
        try {
            if (!env.getProperty(ElasticsearchOptions.PREFIX + "enabled", Boolean.class, false)) return CatalogSearch.disabled();
            var options = ElasticsearchOptions.from(env, false);
            return new ElasticsearchCatalogSearch(new ElasticHttpTransport(options, mapper, Duration.ofMillis(1500)),
                    mapper, options.alias, Clock.systemUTC());
        } catch (RuntimeException invalid) {
            LoggerFactory.getLogger(ElasticsearchConfiguration.class)
                    .error("Invalid Elasticsearch configuration; catalog remains on PostgreSQL");
            return CatalogSearch.disabled();
        }
    }
}
