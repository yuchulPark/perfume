package com.perfume.search;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import com.perfume.domain.Brand;
import com.perfume.domain.Perfume;
import com.perfume.repository.PerfumeRepository;

/** Actual JPQL on a disposable in-memory H2 database, never DB_URL or PostgreSQL. */
@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:catalog-regression;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never", "spring.jpa.show-sql=false", "scentrev.brand-import=false"}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CatalogRepositoryRegressionTests {
    @Autowired TestEntityManager entities;
    @Autowired PerfumeRepository perfumes;

    @Test void existingLikeSearchAndBrandFilterKeepStablePagesAndLiteralWildcards() {
        var creed = entities.persist(new Brand("Creed", "creed")); var other = entities.persist(new Brand("Other", "other"));
        var first = perfume("Aventus", creed); var second = perfume("Silver", creed);
        var korean = perfume("장미 향수", other); var literal = perfume("A%B_C!", other);
        entities.flush(); entities.clear();
        var page = perfumes.findCatalogPage("%creed%", null, PageRequest.of(0, 1));
        assertThat(page.getTotalElements()).isEqualTo(2); assertThat(page.getTotalPages()).isEqualTo(2);
        assertThat(page.getContent()).extracting("id").containsExactly(first.getId());
        assertThat(perfumes.findCatalogPage("%creed%", null, PageRequest.of(1, 1)).getContent()).extracting("id").containsExactly(second.getId());
        assertThat(perfumes.findCatalogPage("%장미%", null, PageRequest.of(0, 20)).getContent()).extracting("id").containsExactly(korean.getId());
        assertThat(perfumes.findCatalogPage("%a!%b!_c!!%", null, PageRequest.of(0, 20)).getContent()).extracting("id").containsExactly(literal.getId());
        assertThat(perfumes.findCatalogPage("%aventus%", "other", PageRequest.of(0, 20))).isEmpty();
        assertThat(perfumes.findCatalogByIds(List.of(first.getId(), korean.getId()), "creed")).extracting("id").containsExactly(first.getId());
    }
    private Perfume perfume(String name, Brand brand) {
        String id = java.util.UUID.randomUUID().toString();
        return entities.persist(new Perfume(id, id, name, null, null, null, brand));
    }
}
