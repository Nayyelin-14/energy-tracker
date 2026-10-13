package com.energytracker.insightservice.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

        @Bean
        RestTemplate restTemplate(
                        RestTemplateBuilder builder,
                        @Value("${usage.service.connect-timeout-ms:2000}") long connectTimeoutMs,
                        @Value("${usage.service.read-timeout-ms:8000}") long readTimeoutMs) {
                return builder
                                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                                .readTimeout(Duration.ofMillis(readTimeoutMs))
                                .build();
        }
}
