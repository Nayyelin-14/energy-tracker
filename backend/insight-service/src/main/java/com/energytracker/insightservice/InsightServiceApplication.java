package com.energytracker.insightservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InsightServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightServiceApplication.class, args);
	}

}
