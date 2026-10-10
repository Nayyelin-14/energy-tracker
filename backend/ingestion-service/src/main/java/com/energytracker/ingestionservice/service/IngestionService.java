package com.energytracker.ingestionservice.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.energytracker.ingestionservice.dto.EnergyUsageDto;
import com.energytracker.kafka.event.EnergyUsageEvent;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class IngestionService {

        private final KafkaTemplate<String, EnergyUsageEvent> kafkaTemplate;

        public void ingestEnergyUsage(EnergyUsageDto input) {

                EnergyUsageEvent usageEvent = EnergyUsageEvent.builder()
                                .deviceId(input.deviceId())
                                .energyCosumed(input.energyConsumed())
                                .timestamp(input.timestamp())
                                .build();

                kafkaTemplate.send("energy-usage", usageEvent);
                log.info("Ingested energy Usage event : {}", usageEvent);
        }
}