package com.energytracker.ingestionservice.simulation;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.energytracker.ingestionservice.dto.EnergyUsageDto;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component

public class ParallelDataSimulation implements CommandLineRunner {
        private final RestTemplate restTemplate = new RestTemplate();

        private final DeviceIdProvider deviceIdsProvider;

        private final ExecutorService executorService;

        public ParallelDataSimulation(DeviceIdProvider deviceIdsProvider) {
                this.deviceIdsProvider = deviceIdsProvider;
                this.executorService = Executors.newCachedThreadPool();
        }

        @Value("${simulation.interval-ms}")
        private int intervalMs;

        @Value("${simulation.parallel-threads}")
        private int parallelThreads;

        @Value("${simulation.requests-per-interval}")
        private int requestsPerInterval;

        @Value("${simulation.endpoint}")
        private String ingestionEndpoint;

        @Override
        public void run(String... args) throws Exception {
                log.info("Parallel Data simulation started....");
                ((ThreadPoolExecutor) executorService).setCorePoolSize(parallelThreads);
        }

        @Scheduled(fixedRateString = "${simulation.interval-ms}")
        public void sendMockData() {

                deviceIdsProvider.refresh();
                if (deviceIdsProvider.nextId().isEmpty()) {
                        log.warn("No devices known to device-service, skipping simulation tick");
                        return;
                }

                int batchSize = requestsPerInterval / parallelThreads;
                int remainder = requestsPerInterval % parallelThreads;

                for (int i = 0; i < parallelThreads; i++) {
                        int requestForThread = batchSize + (i < remainder ? 1 : 0);

                        executorService.submit(() -> {
                                for (int j = 0; j < requestForThread; j++) {
                                        // safe: the cache is only mutated by refresh() on the
                                        // scheduling thread, which already completed above
                                        long deviceId = deviceIdsProvider.nextId().getAsLong();

                                        EnergyUsageDto dto = EnergyUsageDto.builder()
                                                        .deviceId(deviceId)
                                                        .energyConsumed(Math.round(
                                                                        ThreadLocalRandom.current().nextDouble(0.0, 2.0)
                                                                                        * 100.0) / 100.0)
                                                        .timestamp(LocalDateTime.now()
                                                                        .atZone(ZoneId.systemDefault()).toInstant())
                                                        .build();

                                        try {
                                                HttpHeaders headers = new HttpHeaders();
                                                headers.setContentType(MediaType.APPLICATION_JSON);

                                                HttpEntity<EnergyUsageDto> request = new HttpEntity<EnergyUsageDto>(dto,
                                                                headers);
                                                restTemplate.postForEntity(ingestionEndpoint, request, void.class);

                                                log.info("Sent mock data : {}", dto);
                                        } catch (Exception e) {
                                                log.error("Failed to send data : {}", e.getMessage());
                                        }
                                }
                        });
                }
        }

        @PreDestroy
        public void shutdown() {
                executorService.shutdown();
        }
}
