package com.perfume.scentrev.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ScentRevJsonPersistenceConfigurationTests {
    @Test
    void hibernateJsonRoundTripPreservesExactDecimalAndBigintValuesWithoutChangingApplicationMapper() {
        var applicationMapper = new ObjectMapper(); var settings = new HashMap<String, Object>();
        settings.put("hibernate.hbm2ddl.auto", "validate");
        new ScentRevJsonPersistenceConfiguration().scentRevJsonPrecision(applicationMapper).customize(settings);
        var format = (JacksonJsonFormatMapper) settings.get("hibernate.type.json_format_mapper");
        String raw = "{\"score\":0.912345678901234567890123456789,\"n_records\":4294967297,\"unknown\":[null,0.81234567890123456789]}";
        JsonNode restored = format.fromString(raw, JsonNode.class);
        assertThat(restored.path("score").decimalValue()).isEqualByComparingTo("0.912345678901234567890123456789");
        assertThat(restored.path("n_records").longValue()).isEqualTo(4294967297L);
        JsonNode reread = format.fromString(format.toString(restored, JsonNode.class), JsonNode.class);
        assertThat(reread).isEqualTo(restored);
        assertThat(reread.path("unknown").get(1).decimalValue()).isEqualByComparingTo("0.81234567890123456789");
        assertThat(settings.get("hibernate.hbm2ddl.auto")).isEqualTo("validate");
        assertThat(applicationMapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)).isFalse();
    }
}
