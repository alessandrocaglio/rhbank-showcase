package com.showcase.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@EnableRetry
@SpringBootApplication
public class TransactionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionEngineApplication.class, args);
    }
}
