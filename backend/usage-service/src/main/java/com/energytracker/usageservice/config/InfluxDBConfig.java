package com.energytracker.usageservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;

@Configuration
public class InfluxDBConfig {

        @Value("${influx.url}")
        private String influxUrl;

        @Value("${influx.token}")
        private String influxToken;

        @Value("${influx.org}")
        private String influxOrg;

        @Bean
        public InfluxDBClient influxDBClient() {
                final String influxUrl2 = influxUrl;
                if (influxUrl2 != null) {
                        return InfluxDBClientFactory.create(influxUrl2, influxToken.toCharArray(), influxOrg);
                } else {
                        // TODO handle null value
                        return null;
                }
        }
}