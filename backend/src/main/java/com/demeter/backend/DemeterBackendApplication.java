package com.demeter.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemeterBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemeterBackendApplication.class, args);
    }
}
