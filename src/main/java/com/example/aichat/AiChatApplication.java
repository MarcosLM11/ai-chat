package com.example.aichat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AiChatApplication {

    static void main(String[] args) {
        SpringApplication.run(AiChatApplication.class, args);
    }
}