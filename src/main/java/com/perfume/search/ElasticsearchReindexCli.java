package com.perfume.search;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Explicit CLI intercepted before Spring/JPA/importers/RAG/WebSocket initialization. */
public final class ElasticsearchReindexCli {
    private ElasticsearchReindexCli() { }

    public static boolean runIfRequested(String[] args) {
        if (Arrays.stream(args).noneMatch(a -> a.startsWith("--elasticsearch-reindex"))) return false;
        if (args.length != 1 || !args[0].equals("--elasticsearch-reindex=full"))
            throw new IllegalArgumentException("Use only --elasticsearch-reindex=full; credentials must be in environment/secret files");
        var env = new StandardEnvironment();
        var options = ElasticsearchOptions.from(env, true);
        var mapper = new ObjectMapper();
        var transport = new ElasticHttpTransport(options, mapper, Duration.ofSeconds(30));
        var source = new DriverManagerDataSource(env.getRequiredProperty("DB_URL"),
                env.getRequiredProperty("DB_USERNAME"), env.getRequiredProperty("DB_PASSWORD"));
        // No Spring context, no Hibernate, no migrations, no table writes, no scheduled work.
        try (var snapshot = new JdbcCatalogSnapshot(source)) {
            var result = new ElasticsearchReindexer(transport, mapper, options.alias, Clock.systemUTC()).execute(snapshot);
            System.out.printf("Catalog reindex: source=%d indexed=%d index=%s previous=%s alias=%s%n",
                    result.sourceCount(), result.indexedCount(), result.index(), result.previousIndex(), options.alias);
        } catch (RuntimeException failure) {
            // A failed/uncertain alias request must be checked by the operator. Avoid raw JDBC exception data.
            throw new IllegalStateException("Catalog reindex failed. Existing indices retained; inspect alias and counts before retrying.");
        }
        return true;
    }
}
