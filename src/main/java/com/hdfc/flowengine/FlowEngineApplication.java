package com.hdfc.flowengine;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlowEngineApplication {

	public static void main(String[] args) {
		// pgjdbc sends the JVM's default timezone as a connection startup parameter; on some
		// Windows machines this resolves to a legacy alias Postgres's tzdata rejects outright.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(FlowEngineApplication.class, args);
	}
}
