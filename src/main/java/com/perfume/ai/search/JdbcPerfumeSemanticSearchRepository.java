package com.perfume.ai.search;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import com.perfume.ai.embedding.EmbeddingVector;
import com.perfume.ai.search.PerfumeSemanticSearchResponse.Hit;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Only scalar Top-K leaves PostgreSQL. Plain bound JDBC avoids query-vector parameter logging. */
public class JdbcPerfumeSemanticSearchRepository {
    static final String SEARCH_SQL = """
            SELECT perfume_id, name, brand_name, 1 - cosine_distance AS similarity
            FROM (
                SELECT e.perfume_id, p.name, b.name AS brand_name,
                    e.embedding OPERATOR(public.<=>) CAST(? AS public.vector(1024)) AS cosine_distance
                FROM ai.perfume_embeddings e
                JOIN public.perfumes p ON p.id = e.perfume_id
                JOIN public.brands b ON b.id = p.brand_id
                WHERE e.model_name = ? AND e.model_digest = ? AND e.text_version = ?
            ) matched
            ORDER BY cosine_distance ASC, perfume_id ASC
            LIMIT ?
            """;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;
    private final int timeoutSeconds;
    public JdbcPerfumeSemanticSearchRepository(DataSource dataSource, int timeoutSeconds) {
        this.dataSource = dataSource; this.timeoutSeconds = timeoutSeconds;
        var manager = new DataSourceTransactionManager(dataSource); manager.setEnforceReadOnly(true);
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true); transaction.setTimeout(timeoutSeconds);
    }
    public List<Hit> search(float[] vector, EmbeddingModelIdentity model, int topK) {
        if (topK < 1 || topK > 20) { throw new SemanticSearchFailure(SemanticSearchFailure.Code.INVALID_REQUEST); }
        var literal = EmbeddingVector.literal(vector);
        try {
            return transaction.execute(status -> {
                var connection = DataSourceUtils.getConnection(dataSource);
                try (var statement = connection.prepareStatement(SEARCH_SQL)) {
                    statement.setQueryTimeout(timeoutSeconds);
                    statement.setString(1, literal); statement.setString(2, model.name());
                    statement.setString(3, model.digest()); statement.setString(4, model.textVersion()); statement.setInt(5, topK);
                    var results = new ArrayList<Hit>();
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            long id = rows.getLong("perfume_id"); boolean missingId = rows.wasNull();
                            var name = rows.getString("name"); var brand = rows.getString("brand_name");
                            double similarity = rows.getDouble("similarity");
                            if (missingId || rows.wasNull() || id < 1 || name == null || brand == null || !Double.isFinite(similarity)
                                    || similarity < -1.000001 || similarity > 1.000001 || results.size() >= topK) {
                                throw new SemanticSearchFailure(SemanticSearchFailure.Code.PGVECTOR_SEARCH_ERROR);
                            }
                            results.add(new Hit(id, name, brand, Math.max(-1, Math.min(1, similarity))));
                        }
                    }
                    return List.copyOf(results);
                } catch (SQLException failure) { throw sqlFailure(failure); }
                finally { DataSourceUtils.releaseConnection(connection, dataSource); }
            });
        } catch (CannotCreateTransactionException unavailable) {
            throw new SemanticSearchFailure(SemanticSearchFailure.Code.DATABASE_UNAVAILABLE);
        } catch (TransactionException failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof SQLException sql) { throw sqlFailure(sql); }
            }
            throw new SemanticSearchFailure(SemanticSearchFailure.Code.PGVECTOR_SEARCH_ERROR);
        }
    }
    private static SemanticSearchFailure sqlFailure(SQLException failure) {
        var state = failure.getSQLState();
        return new SemanticSearchFailure(failure instanceof SQLTimeoutException || "57014".equals(state)
                ? SemanticSearchFailure.Code.DATABASE_TIMEOUT
                : state != null && state.startsWith("08") ? SemanticSearchFailure.Code.DATABASE_UNAVAILABLE
                : SemanticSearchFailure.Code.PGVECTOR_SEARCH_ERROR);
    }
}
