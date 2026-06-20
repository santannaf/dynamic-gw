package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints({GatewayRuntimeHints.class, Fabric8RuntimeHints.class, Fabric8ModelRuntimeHints.class,
        AwsRuntimeHints.class})
public class GatewayApplication {
    static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}
