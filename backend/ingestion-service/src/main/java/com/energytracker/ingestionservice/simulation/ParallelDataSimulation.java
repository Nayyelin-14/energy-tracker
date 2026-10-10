package com.energytracker.ingestionservice.simulation;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

        private final Random random = new Random();

        private final ExecutorService executorService;

        public ParallelDataSimulation() {
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

                int batchSize = requestsPerInterval / parallelThreads;
                int remainder = requestsPerInterval % parallelThreads;

                for (int i = 0; i < parallelThreads; i++) {
                        int requestForThread = batchSize + (i < remainder ? 1 : 0);

                        executorService.submit(() -> {
                                for (int j = 0; j < requestForThread; j++) {
                                        EnergyUsageDto dto = EnergyUsageDto.builder()
                                                        .deviceId(random.nextLong(1, 6))
                                                        .energyConsumed(Math.round(random.nextDouble(0.0, 2.0) * 100.0)
                                                                        / 100.0)
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
