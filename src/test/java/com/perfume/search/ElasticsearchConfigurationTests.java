package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.mock.env.MockEnvironment;
import com.fasterxml.jackson.databind.ObjectMapper;

class ElasticsearchConfigurationTests {
    @TempDir Path directory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ElasticsearchConfiguration.class, JsonConfig.class);

    @Test void defaultNeedsNeitherCredentialsNorAClientOrDatabase() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(CatalogSearch.class);
            assertThat(context.getBean(CatalogSearch.class).search(0, 20, "rose", null)).isEmpty();
            assertThat(context).doesNotHaveBean(ElasticHttpTransport.class);
        });
    }
    @Test void invalidOptInKeepsApplicationAvailableOnPostgresql() {
        runner.withPropertyValues("perfume.elasticsearch.enabled=true", "perfume.elasticsearch.url=http://invalid")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CatalogSearch.class).search(0, 20, "rose", null)).isEmpty();
                });
    }
    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:9200", "https://user:secret@localhost:9200", "https://localhost:9200/path", "https://localhost:9200/?key=secret", "https://localhost:9200/#fragment"})
    void unsafeEndpointsAreRejectedWithoutEchoingSecrets(String uri) {
        assertThatThrownBy(() -> ElasticsearchOptions.from(new MockEnvironment().withProperty("perfume.elasticsearch.url", uri), false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret").hasNoCause();
    }
    @Test void secretFileIsReadOnlyInRequestedModeAndAliasCannotInjectPaths() throws Exception {
        Path key = directory.resolve("key"); Files.writeString(key, "encoded-key==\n");
        var env = new MockEnvironment().withProperty("perfume.elasticsearch.url", "https://elasticsearch:9200")
                .withProperty("perfume.elasticsearch.api-key-file", key.toString())
                .withProperty("perfume.elasticsearch.ca-file", directory.resolve("ca.crt").toString());
        assertThat(ElasticsearchOptions.from(env, false).apiKey).isEqualTo("encoded-key==");
        assertThatThrownBy(() -> ElasticsearchOptions.from(env, true)).isInstanceOf(IllegalArgumentException.class);
        env.withProperty("perfume.elasticsearch.alias", "../_all");
        assertThatThrownBy(() -> ElasticsearchOptions.from(env, false)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void reindexCannotBeEnabledByOrdinaryWebArguments() {
        assertThat(ElasticsearchReindexCli.runIfRequested(new String[]{"--server.port=8081"})).isFalse();
        assertThatThrownBy(() -> ElasticsearchReindexCli.runIfRequested(new String[]{"--elasticsearch-reindex=true"})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ElasticsearchReindexCli.runIfRequested(new String[]{"--elasticsearch-reindex=full", "--scentrev.brand-import=true"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @TestConfiguration(proxyBeanMethods = false) static class JsonConfig { @Bean ObjectMapper mapper() { return new ObjectMapper(); } }
}
