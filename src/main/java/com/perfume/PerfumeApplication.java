package com.perfume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;
import com.perfume.ai.embedding.EmbeddingBatchOptions;
import com.perfume.ai.embedding.PerfumeEmbeddingBatchRunner;

@SpringBootApplication
public class PerfumeApplication {

	public static void main(String[] args) {
		var application = new SpringApplication(PerfumeApplication.class);
		ScentRevSingleBrandImportRunner.configureManualExecution(application);
		PerfumeEmbeddingBatchRunner.configureManualExecution(application);
		var context = application.run(args);
		if (context.getEnvironment().getProperty(ScentRevSingleBrandImportRunner.ENABLED_PROPERTY, Boolean.class, false)
				|| context.getEnvironment().getProperty(EmbeddingBatchOptions.ENABLED, Boolean.class, false)) {
			context.close(); // Explicit manual jobs finish and close the datasource/MCP client.
		}
	}

}
