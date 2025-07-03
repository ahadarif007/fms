package com.bracit.fms.fms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class FmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(FmsApplication.class, args);
	}

}
