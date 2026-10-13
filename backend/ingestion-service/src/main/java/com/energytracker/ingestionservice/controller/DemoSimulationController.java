package com.energytracker.ingestionservice.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.energytracker.ingestionservice.client.DeviceClient;
import com.energytracker.ingestionservice.dto.DeviceDto;
import com.energytracker.kafka.event.EnergyUsageEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Demo simulation endpoint that sends energy readings through the existing
 * ingestion pipeline (Kafka → usage-service → InfluxDB → threshold check).
 *
 * Two modes:
 * - normal: sends 10 small readings (0.5–2.0 kWh each)
 * - high:   sends 5 large readings (100–150 kWh each) that sum well above
 *           typical thresholds, ensuring a deterministic threshold violation.
 *
 * Readings use timestamps within the last 5 minutes so the 1-hour threshold
 * aggregation window picks them up on its next 10-second cycle.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ingestion/simulate")
@RequiredArgsConstructor
public class DemoSimulationController {

        private final KafkaTemplate<String, EnergyUsageEvent> kafkaTemplate;
        private final DeviceClient deviceClient;

        @PostMapping("/device/{deviceId}")
        public ResponseEntity<Map<String, Object>> simulate(
                        @PathVariable Long deviceId,
                        @RequestParam(defaultValue = "normal") String mode) {

                // Validate device exists
                final DeviceDto device;
                try {
                        device = deviceClient.getDeviceById(deviceId);
                } catch (Exception e) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                        "Device not found: " + deviceId);
                }

                final int readings;
                final double minEnergy;
                final double maxEnergy;

                if ("high".equalsIgnoreCase(mode)) {
                        readings = 5;
                        minEnergy = 100.0;
                        maxEnergy = 150.0;
                } else {
                        readings = 10;
                        minEnergy = 0.5;
                        maxEnergy = 2.0;
                }

                final Instant now = Instant.now();
                double totalSent = 0;

                for (int i = 0; i < readings; i++) {
                        final double energy = Math.round(
                                        (minEnergy + Math.random() * (maxEnergy - minEnergy)) * 100.0) / 100.0;
                        // Timestamps within the last 5 minutes so they fall inside the
                        // 1-hour aggregation window used by the threshold checker
                        final Instant timestamp = now.minusSeconds((long) (Math.random() * 300));

                        final EnergyUsageEvent event = EnergyUsageEvent.builder()
                                        .deviceId(deviceId)
                                        .energyCosumed(energy)
                                        .timestamp(timestamp)
                                        .build();

                        kafkaTemplate.send("energy-usage", event);
                        totalSent += energy;
                }

                final double roundedTotal = Math.round(totalSent * 100.0) / 100.0;
                log.info("Simulated {} '{}' readings for device '{}' (id={}), total={} kWh",
                                readings, mode, device.name(), deviceId, roundedTotal);

                return ResponseEntity.accepted().body(Map.of(
                                "status", "accepted",
                                "deviceId", deviceId,
                                "deviceName", device.name() != null ? device.name() : "",
                                "mode", mode,
                                "readingsSent", readings,
                                "totalEnergyKwh", roundedTotal,
                                "message", "Readings sent through the ingestion pipeline. "
                                                + "The threshold checker runs every 10 seconds — "
                                                + "check alerts shortly."));
        }
}
