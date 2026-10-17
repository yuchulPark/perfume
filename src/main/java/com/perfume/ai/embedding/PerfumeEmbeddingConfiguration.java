package com.perfume.ai.embedding;

import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = EmbeddingBatchOptions.ENABLED, havingValue = "true")
public class PerfumeEmbeddingConfiguration {
    @Bean
    EmbeddingBatchOptions embeddingBatchOptions(Environment environment) { return EmbeddingBatchOptions.from(environment); }

    @Bean
    PerfumeEmbeddingText perfumeEmbeddingText() { return new PerfumeEmbeddingText(); }

    @Bean
    PerfumeEmbeddingStore perfumeEmbeddingStore(DataSource dataSource, ObjectMapper mapper) {
        return new JdbcPerfumeEmbeddingStore(dataSource, mapper);
    }

    @Bean
    PerfumeEmbeddingClient perfumeEmbeddingClient(RestClient.Builder builder, EmbeddingBatchOptions options) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(options.connectTimeout());
        factory.setReadTimeout(options.readTimeout());
        return new OllamaPerfumeEmbeddingClient(builder.clone().baseUrl(options.baseUrl().toString()).requestFactory(factory).build());
    }

    @Bean
    PerfumeEmbeddingBatch perfumeEmbeddingBatch(PerfumeEmbeddingStore store, PerfumeEmbeddingClient client, PerfumeEmbeddingText texts) {
        return new PerfumeEmbeddingBatch(store, client, texts);
    }

    @Bean
    @ConditionalOnProperty(name = AutoEmbeddingOptions.ENABLED, havingValue = "false", matchIfMissing = true)
    PerfumeEmbeddingBatchRunner perfumeEmbeddingBatchRunner(PerfumeEmbeddingBatch batch, EmbeddingBatchOptions options) {
        return new PerfumeEmbeddingBatchRunner(batch, options);
    }

    @Bean
    @ConditionalOnProperty(name = AutoEmbeddingOptions.ENABLED, havingValue = "true")
    AutoPerfumeEmbeddingRunner autoPerfumeEmbeddingRunner(PerfumeEmbeddingStore store, PerfumeEmbeddingBatch batch,
            EmbeddingBatchOptions options, Environment environment) {
        return new AutoPerfumeEmbeddingRunner(new AutoPerfumeEmbeddingJob(store, batch), options, AutoEmbeddingOptions.from(environment));
    }
}
