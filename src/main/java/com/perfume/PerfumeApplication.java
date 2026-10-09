package com.perfume;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.perfume.scentrev.service.ScentRevSingleBrandImportRunner;

@SpringBootApplication
public class PerfumeApplication {

	public static void main(String[] args) {
		var application = new SpringApplication(PerfumeApplication.class);
		ScentRevSingleBrandImportRunner.configureManualExecution(application);
		var context = application.run(args);
		if (context.getEnvironment().getProperty(ScentRevSingleBrandImportRunner.ENABLED_PROPERTY, Boolean.class, false)) {
			context.close(); // One manual brand import finishes normally and closes the datasource/MCP client.
		}
	}

}
