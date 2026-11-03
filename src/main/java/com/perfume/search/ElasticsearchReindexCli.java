package com.perfume.search;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.perfume.search.SearchDiagnostics.*;

/** Explicit CLI intercepted before Spring/JPA/importers/RAG/WebSocket initialization. */
public final class ElasticsearchReindexCli {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElasticsearchReindexCli.class);
    private ElasticsearchReindexCli() { }

    public static boolean runIfRequested(String[] args) {
        if (Arrays.stream(args).noneMatch(a -> a.startsWith("--elasticsearch-reindex"))) return false;
        if (args.length != 1 || !args[0].equals("--elasticsearch-reindex=full"))
            throw new IllegalArgumentException("Use only --elasticsearch-reindex=full; credentials must be in environment/secret files");
        execute(new StandardEnvironment());
        return true;
    }

    static void execute(Environment env) {
        Stage stage = Stage.CONFIGURATION;
        begin(log, stage);
        try {
            var options = ElasticsearchOptions.from(env, true);
            var mapper = new ObjectMapper();
            var transport = new ElasticHttpTransport(options, mapper, Duration.ofSeconds(30));
            var source = dataSource(env.getRequiredProperty("DB_URL"),
                    env.getRequiredProperty("DB_USERNAME"), env.getRequiredProperty("DB_PASSWORD"));
            stage = Stage.SNAPSHOT_OPEN;
            // No Spring context, no Hibernate, no migrations, no table writes, no scheduled work.
            try (var snapshot = new JdbcCatalogSnapshot(source)) {
                stage = Stage.SOURCE_COUNT;
                var result = new ElasticsearchReindexer(transport, mapper, options.alias, Clock.systemUTC()).execute(snapshot);
                stage = Stage.SNAPSHOT_CLOSE;
                System.out.printf("Catalog reindex: source=%d indexed=%d index=%s previous=%s alias=%s%n",
                        result.sourceCount(), result.indexedCount(), result.index(), result.previousIndex(), options.alias);
            }
        } catch (RuntimeException failure) {
            Stage failedAt = failure instanceof Failure known ? known.stage : stage;
            var safe = report(log, failedAt, failure);
            // Keep a sanitized cause and diagnostic fields, never the original exception/cause/suppressed chain.
            throw new IllegalStateException("Catalog reindex failed. " + safe.summary()
                    + ". Existing indices retained; inspect alias and counts before retrying.", safe);
        }
    }

    static DriverManagerDataSource dataSource(String url, String username, String password) {
        return new DriverManagerDataSource(url, username, password) {
            @Override protected java.sql.Connection getConnectionFromDriver(java.util.Properties properties)
                    throws java.sql.SQLException {
                // The parent implementation logs the complete JDBC URL at DEBUG, before Spring logging starts.
                // Use the identical DriverManager path without that URL-bearing diagnostic.
                return getConnectionFromDriverManager(getUrl(), properties);
            }
        };
    }
}
