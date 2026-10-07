package com.perfume;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Start the real application context without opening JDBC connections or changing its schema.
@SpringBootTest(properties = {
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
		"spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
		"spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
		"spring.sql.init.mode=never"
})
class PerfumeApplicationTests {

	@Test
	void contextLoads() {
	}

}
