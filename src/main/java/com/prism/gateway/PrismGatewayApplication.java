package com.prism.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PrismGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrismGatewayApplication.class, args);
    }
}
