package com.perfume.scentrev.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Preserve source decimal precision on JSONB reads as well as initial provider decoding. */
@Configuration(proxyBeanMethods = false)
public class ScentRevJsonPersistenceConfiguration {
    @Bean
    public HibernatePropertiesCustomizer scentRevJsonPrecision(ObjectMapper mapper) {
        var jsonMapper = new JacksonJsonFormatMapper(mapper.copy()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        return properties -> properties.put("hibernate.type.json_format_mapper", jsonMapper);
    }
}
