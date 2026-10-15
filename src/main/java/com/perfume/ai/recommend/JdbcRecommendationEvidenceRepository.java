package com.perfume.ai.recommend;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import com.perfume.ai.embedding.EmbeddingModelIdentity;
import com.perfume.ai.embedding.PerfumeEmbeddingText;
import com.perfume.ai.search.PerfumeSemanticSearchResponse.Hit;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** One bounded SELECT for only the search hits. No vector fetch, batch session, advisory lock or write. */
public class JdbcRecommendationEvidenceRepository {
    static final int MAX_SOURCE_CHARACTERS = 65536;
    static final String SQL = """
            SELECT e.perfume_id, e.model_name, e.model_digest, e.text_version,
                left(e.source_text, 65537) AS source_text, char_length(e.source_text) AS source_length,
                e.source_sha256, e.updated_at, p.name, b.name AS brand_name
            FROM ai.perfume_embeddings e
            JOIN public.perfumes p ON p.id = e.perfume_id
            JOIN public.brands b ON b.id = p.brand_id
            WHERE e.model_name = ? AND e.model_digest = ? AND e.text_version = ?
                AND e.perfume_id IN (%s)
            ORDER BY e.perfume_id
            """;
    private final DataSource source;
    private final EmbeddingModelIdentity model;
    private final TransactionTemplate transaction;
    private final int timeout;
    public JdbcRecommendationEvidenceRepository(DataSource source, EmbeddingModelIdentity model, int timeout) {
        this.source = source; this.model = model; this.timeout = timeout;
        var manager = new DataSourceTransactionManager(source); manager.setEnforceReadOnly(true);
        transaction = new TransactionTemplate(manager); transaction.setReadOnly(true); transaction.setTimeout(timeout);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public List<RecommendationEvidence> find(List<Hit> hits) {
        if (hits.isEmpty()) { return List.of(); }
        if (hits.size() > 20 || hits.stream().anyMatch(hit -> hit.perfumeId() < 1)
                || hits.stream().map(Hit::perfumeId).distinct().count() != hits.size()) { throw unavailable(); }
        var expected = hits.stream().collect(Collectors.toMap(Hit::perfumeId, hit -> hit));
        try {
            return transaction.execute(status -> {
                var connection = DataSourceUtils.getConnection(source);
                try (var statement = connection.prepareStatement(SQL.formatted(hits.stream().map(hit -> "?").collect(Collectors.joining(","))))) {
                    statement.setQueryTimeout(timeout);
                    statement.setString(1, model.name()); statement.setString(2, model.digest()); statement.setString(3, model.textVersion());
                    for (int index = 0; index < hits.size(); index++) { statement.setLong(index + 4, hits.get(index).perfumeId()); }
                    var found = new HashMap<Long, RecommendationEvidence>();
                    try (var rows = statement.executeQuery()) {
                        while (rows.next()) {
                            long id = rows.getLong("perfume_id"); var hit = expected.get(id);
                            var text = rows.getString("source_text"); int length = rows.getInt("source_length");
                            var hash = rows.getString("source_sha256"); var at = rows.getTimestamp("updated_at");
                            if (hit == null || found.containsKey(id) || !model.name().equals(rows.getString("model_name"))
                                    || !model.digest().equals(rows.getString("model_digest")) || !model.textVersion().equals(rows.getString("text_version"))
                                    || !hit.name().equals(rows.getString("name")) || !hit.brandName().equals(rows.getString("brand_name"))
                                    || text == null || text.isBlank() || length > MAX_SOURCE_CHARACTERS || length != text.codePointCount(0, text.length())
                                    || at == null || !new PerfumeEmbeddingText().hash(text).equals(hash)) { throw unavailable(); }
                            found.put(id, new RecommendationEvidence(hits.indexOf(hit) + 1, hit, text, at.toInstant()));
                        }
                    }
                    if (found.size() != hits.size()) { throw unavailable(); }
                    return hits.stream().map(hit -> found.get(hit.perfumeId())).toList();
                } catch (SQLException failure) { throw sqlFailure(failure); }
                finally { DataSourceUtils.releaseConnection(connection, source); }
            });
        } catch (CannotCreateTransactionException failure) { throw new RecommendationFailure(RecommendationFailure.Code.DATABASE_UNAVAILABLE); }
        catch (TransactionException failure) {
            Throwable cause = failure;
            for (int depth = 0; cause != null && depth < 32; depth++, cause = cause.getCause()) {
                if (cause instanceof SQLException sql) { throw sqlFailure(sql); }
            }
            throw unavailable();
        }
    }
    private static RecommendationFailure sqlFailure(SQLException failure) {
        var state = failure.getSQLState();
        return new RecommendationFailure(failure instanceof SQLTimeoutException || "57014".equals(state)
                ? RecommendationFailure.Code.DATABASE_TIMEOUT : state != null && state.startsWith("08")
                ? RecommendationFailure.Code.DATABASE_UNAVAILABLE : RecommendationFailure.Code.EVIDENCE_UNAVAILABLE);
    }
    private static RecommendationFailure unavailable() { return new RecommendationFailure(RecommendationFailure.Code.EVIDENCE_UNAVAILABLE); }
}
