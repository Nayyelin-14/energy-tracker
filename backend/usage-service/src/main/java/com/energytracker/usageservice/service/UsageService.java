package com.energytracker.usageservice.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.energytracker.kafka.event.AlertingEvent;
import com.energytracker.kafka.event.EnergyUsageEvent;
import com.energytracker.usageservice.client.DeviceClient;
import com.energytracker.usageservice.client.UserClient;
import com.energytracker.usageservice.dto.DeviceDto;
import com.energytracker.usageservice.dto.UserDto;
import com.energytracker.usageservice.model.DeviceEnergy;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UsageService {

        private final InfluxDBClient influxDBClient;
        private final DeviceClient deviceClient;
        private final UserClient userClient;

        private final KafkaTemplate<String, AlertingEvent> kafkaTemplate;

        @Value("${influx.org}")
        private String influxOrg;

        @Value("${influx.bucket}")
        private String influxBucket;

        public UsageService(
                        InfluxDBClient influxDBClient,
                        DeviceClient deviceClient,
                        UserClient userClient,
                        KafkaTemplate<String, AlertingEvent> kafkaTemplate) {
                this.influxDBClient = influxDBClient;
                this.deviceClient = deviceClient;
                this.kafkaTemplate = kafkaTemplate;
                this.userClient = userClient;
        }

        @KafkaListener(topics = "energy-usage", groupId = "usage-service")
        public void energyUsageEvent(EnergyUsageEvent energyUsageEvent) {
                log.info("Received energy usage event : {}", energyUsageEvent);

                Point point = Point.measurement("energy-usage")
                                .addTag("deviceId", String.valueOf(energyUsageEvent.deviceId()))
                                .addField("energyConsumed", energyUsageEvent.energyCosumed())
                                .time(energyUsageEvent.timestamp(), WritePrecision.MS);

                influxDBClient.getWriteApiBlocking().writePoint(influxBucket, influxOrg, point);

        }

        // run every 10 seconds to get the one hour data of a device
        @Scheduled(cron = "*/10 * * * * *")
        public void aggregateDeviceEnergyUsage() {
                final Instant now = Instant.now();
                final Instant oneHourAgo = now.minusSeconds(3600);

                String fluxQuery = String.format(
                                """
                                                from(bucket: "%s")
                                                |> range (start: time(v: "%s") , stop: time(v: "%s"))
                                                |> filter( fn : (r) => r["_measurement"] == "energy-usage")
                                                |> filter (fn : (r) => r["_field"] == "energyConsumed")
                                                |> group(columns: ["deviceId"])
                                                |> sum(column : "_value")
                                                """, influxBucket, oneHourAgo.toString(), now);

                QueryApi queryApi = influxDBClient.getQueryApi();
                List<FluxTable> tables = queryApi.query(fluxQuery, influxOrg);

                List<DeviceEnergy> deviceEnergies = new ArrayList<>();

                for (FluxTable table : tables) {
                        for (FluxRecord record : table.getRecords()) {

                                Object deviceIdValue = record.getValueByKey("deviceId");
                                Object energyValue = record.getValueByKey("_value");

                                if (deviceIdValue == null) {
                                        log.warn("Skipping record because deviceId is null");
                                        continue;
                                }

                                if (!(energyValue instanceof Number)) {
                                        log.warn("Skipping record because energyConsumed is missing or invalid");
                                        continue;
                                }

                                Long deviceId;
                                try {
                                        deviceId = Long.valueOf(deviceIdValue.toString());
                                } catch (NumberFormatException e) {
                                        log.warn("Skipping record because deviceId is invalid: {}", deviceIdValue);
                                        continue;
                                }

                                Double energyConsumed = ((Number) energyValue).doubleValue();

                                deviceEnergies.add(
                                                DeviceEnergy.builder()
                                                                .deviceId(deviceId)
                                                                .energyConsumed(energyConsumed)
                                                                .build());
                        }
                }

                for (DeviceEnergy deviceEnergy : deviceEnergies) {
                        final DeviceDto deviceResponse;
                        try {
                                deviceResponse = deviceClient.getDeviceById(deviceEnergy.getDeviceId());
                                if (deviceResponse == null || deviceResponse.userId() == null) {
                                        log.warn("Device not found or has no owner for ID: {}",
                                                        deviceEnergy.getDeviceId());
                                        continue;
                                }
                                deviceEnergy.setUserId(deviceResponse.userId());
                        } catch (Exception e) {
                                // a deleted device (or any client error) must not abort
                                // the whole aggregation run
                                log.warn("Failed to fetch device {}: {}", deviceEnergy.getDeviceId(), e.getMessage());
                                continue;
                        }

                }

                // remove device with null userId
                deviceEnergies.removeIf(de -> de.getUserId() == null);

                log.info("Aggregated device energies over  the past one hour : {}", deviceEnergies);

                // get user-device mapping and aggregate per user
                Map<Long, List<DeviceEnergy>> userDeviceEnergyMap = deviceEnergies.stream()
                                .collect(Collectors.groupingBy(DeviceEnergy::getUserId));

                List<Long> userIds = new ArrayList<>(userDeviceEnergyMap.keySet());
                final Map<Long, Double> userthresholdMap = new HashMap<>();
                final Map<Long, String> userEmailMap = new HashMap<>();
                final Map<Long, Boolean> userAlertingMap = new HashMap<>();

                for (final Long userId : userIds) {
                        try {
                                UserDto user = userClient.getUserById(userId);

                                if (user == null || user.userId() == null) {
                                        log.warn("User not found: {}", userId);
                                        continue;
                                }

                                userthresholdMap.put(userId, user.energyAlertingThreshold());
                                userEmailMap.put(userId, user.email());
                                userAlertingMap.put(userId, user.alerting());

                        } catch (Exception e) {
                                log.error("Failed to fetch user: {}", userId, e);
                        }
                }

                // check treasholds against aggregated usage
                // alertedUsers = [101, 102, 103] take the users
                final List<Long> alertedUsers = new ArrayList<>(userthresholdMap.keySet());
                for (final Long userId : alertedUsers) {
                        final Double threshold = userthresholdMap.get(userId);

                        // devices = [
                        // DeviceEnergy(deviceId=1, energyConsumed=5.0, userId=101),
                        // DeviceEnergy(deviceId=2, energyConsumed=3.0, userId=101)
                        // ]
                        final List<DeviceEnergy> devices = userDeviceEnergyMap.get(userId);

                        final Double totalConsumption = devices.stream().mapToDouble(DeviceEnergy::getEnergyConsumed)
                                        .sum();

                        if (totalConsumption > threshold) {

                                log.info("ALERT : USER ID {} has exceeded the energy threshold! ", +userId,
                                                totalConsumption, threshold);

                                // put messgae (send event) on kafka alert-topic as a producer
                                final AlertingEvent alertingEvent = AlertingEvent.builder()
                                                .userId(userId)
                                                .message("Energy consumption threshold exceeded")
                                                .threshold(threshold)
                                                .energyConsumed(totalConsumption)
                                                .email(userEmailMap.get(userId))
                                                .build();

                                // send alrt event to kafka topic

                                kafkaTemplate.send("energy-alerts", alertingEvent);

                        } else {
                                log.info("User ID {} is within the energy threshold. "
                                                + "Total Consumption: {} , Threshold : {}", userId, totalConsumption,
                                                threshold);
                        }
                }
        }

}
