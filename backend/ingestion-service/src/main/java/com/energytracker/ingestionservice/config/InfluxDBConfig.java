package com.energytracker.ingestionservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

@Configuration
public class InfluxDBConfig {

        @Value("${influx.url}")
        private String url;

        @Value("${influx.token}")
        private String token;

        @Bean
        public InfluxDBClient influxDBClient() {
                return InfluxDBClientFactory.create(url, token.toCharArray());
        }
}
