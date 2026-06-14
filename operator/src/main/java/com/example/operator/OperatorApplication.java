package com.example.operator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.ImportRuntimeHints;

@SpringBootApplication
@ImportRuntimeHints({OperatorRuntimeHints.class, Fabric8RuntimeHints.class, Fabric8ModelRuntimeHints.class, AwsRuntimeHints.class})
public class OperatorApplication {

    static void main(String[] args) {
        new SpringApplicationBuilder(OperatorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    public static SpringApplication application() {
        SpringApplication app = new SpringApplication(OperatorApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }
}
