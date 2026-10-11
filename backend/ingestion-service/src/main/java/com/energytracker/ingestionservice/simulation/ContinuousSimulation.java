package com.energytracker.ingestionservice.simulation;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Random;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.energytracker.ingestionservice.dto.EnergyUsageDto;

import lombok.extern.slf4j.Slf4j;

// #CommandLineRunner	Application start ပြီးနောက် code ကို run ဖို့

@Slf4j
@Component
public class ContinuousSimulation implements CommandLineRunner {
        private final RestTemplate restTemplate = new RestTemplate();

        private final DeviceIdProvider deviceIdsProvider;

        private final Random random = new Random();

        public ContinuousSimulation(DeviceIdProvider deviceIdsProvider) {
                this.deviceIdsProvider = deviceIdsProvider;
        }

        @Value("${simulation.interval-ms}")
        private int intervalMs;

        @Value("${simulation.requests-per-interval}")
        private int requestsPerInterval;

        @Value("${simulation.endpoint}")
        private String ingestionEndpoint;

        @Override
        public void run(String... args) throws Exception {
                log.info("Continuous Data simulation started....");
        }

        // @Scheduled(fixedRateString = "${simulation.interval-ms}")
        public void sendMockData() {
                deviceIdsProvider.refresh();
                if (deviceIdsProvider.nextId().isEmpty()) {
                        log.warn("No devices known to device-service, skipping simulation tick");
                        return;
                }

                for (int i = 0; i < requestsPerInterval; i++) {

                        EnergyUsageDto dto = EnergyUsageDto.builder()
                                        .deviceId(deviceIdsProvider.nextId().getAsLong())
                                        .energyConsumed(Math.round(random.nextDouble(0.0, 2.0) * 100.0) / 100.0)
                                        .timestamp(LocalDateTime.now()
                                                        .atZone(ZoneId.systemDefault()).toInstant())
                                        .build();

                        try {
                                HttpHeaders headers = new HttpHeaders();
                                headers.setContentType(MediaType.APPLICATION_JSON);

                                HttpEntity<EnergyUsageDto> request = new HttpEntity<EnergyUsageDto>(dto, headers);
                                restTemplate.postForEntity(ingestionEndpoint, request, void.class);

                                log.info("Sent mock data : {}", dto);
                        } catch (Exception e) {
                                log.error("Failed to send data : {}", e.getMessage());
                        }
                }
        }

}
