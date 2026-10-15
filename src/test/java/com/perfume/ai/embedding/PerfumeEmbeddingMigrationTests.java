package com.perfume.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PerfumeEmbeddingMigrationTests {
    @Test
    void sqlRemainsManualAndNeverAltersOrDeletesPublicCatalogData() throws Exception {
        var sql = Files.readString(Path.of("docs/sql/perfume-embeddings-v1.sql"));
        assertThat(sql).contains("CREATE SCHEMA ai", "CREATE TABLE ai.perfume_embeddings", "public.vector(1024)",
                "PRIMARY KEY (perfume_id, model_name, model_digest, text_version)", "REFERENCES public.perfumes(id) ON DELETE RESTRICT")
                .doesNotContain("CREATE EXTENSION", "IF NOT EXISTS ai");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(ALTER\\s+TABLE\\s+public\\.|DROP\\s|TRUNCATE\\s|DELETE\\s+FROM|UPDATE\\s+public\\.|INSERT\\s+INTO\\s+public\\.)");
        try (var resources = Files.walk(Path.of("src/main/resources"))) {
            assertThat(resources.filter(Files::isRegularFile).anyMatch(file -> file.getFileName().toString().equals("perfume-embeddings-v1.sql"))).isFalse();
        }
        assertThat(PerfumeEmbeddingDocument.class.isAnnotationPresent(jakarta.persistence.Entity.class)).isFalse();
    }
}
