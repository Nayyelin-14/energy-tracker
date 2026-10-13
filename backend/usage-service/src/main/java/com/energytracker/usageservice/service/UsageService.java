package com.energytracker.usageservice.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import com.energytracker.usageservice.dto.DeviceSeriesDto;
import com.energytracker.usageservice.dto.EnergyPointDto;
import com.energytracker.usageservice.dto.UsageCoverageDto;
import com.energytracker.usageservice.dto.UsageDto;
import com.energytracker.usageservice.dto.UsageSeriesDto;
import com.energytracker.usageservice.dto.UserDto;
import com.energytracker.usageservice.model.Device;
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

        /**
         * Tracks which users currently have an active threshold violation.
         * Used to alert only on the below→above transition, not on every
         * scheduled execution during a sustained violation.
         */
        private final Map<Long, Boolean> userBreachState = new ConcurrentHashMap<>();

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

                // check thresholds against aggregated usage
                final List<Long> alertedUsers = new ArrayList<>(userthresholdMap.keySet());
                for (final Long userId : alertedUsers) {
                        final Double threshold = userthresholdMap.get(userId);
                        final List<DeviceEnergy> devices = userDeviceEnergyMap.get(userId);

                        final Double totalConsumption = devices.stream().mapToDouble(DeviceEnergy::getEnergyConsumed)
                                        .sum();

                        final boolean aboveThreshold = totalConsumption > threshold;
                        final boolean wasAboveThreshold = userBreachState.getOrDefault(userId, false);

                        if (aboveThreshold && !wasAboveThreshold) {
                                // Transition below→above: alert fires exactly once per violation
                                userBreachState.put(userId, true);

                                if (Boolean.TRUE.equals(userAlertingMap.get(userId))) {
                                        log.info("ALERT: User {} exceeded threshold. Consumed={}, Threshold={}",
                                                        userId, totalConsumption, threshold);

                                        final AlertingEvent alertingEvent = AlertingEvent.builder()
                                                        .userId(userId)
                                                        .message("Energy consumption threshold exceeded")
                                                        .threshold(threshold)
                                                        .energyConsumed(totalConsumption)
                                                        .email(userEmailMap.get(userId))
                                                        .build();

                                        kafkaTemplate.send("energy-alerts", alertingEvent);
                                } else {
                                        log.info("User {} exceeded threshold but alerting is disabled", userId);
                                }
                        } else if (!aboveThreshold && wasAboveThreshold) {
                                // Consumption returned below threshold: re-arm for next crossing
                                userBreachState.put(userId, false);
                                log.info("User {} is back within threshold. Re-armed alert.", userId);
                        } else if (aboveThreshold) {
                                // Still above threshold (sustained violation): no new alert
                                log.debug("User {} still above threshold, suppressing duplicate alert. Consumed={}",
                                                userId, totalConsumption);
                        } else {
                                log.info("User {} is within the energy threshold. Consumed={}, Threshold={}",
                                                userId, totalConsumption, threshold);
                        }
                }
        }

        public UsageDto getXDaysUsageForUser(Long userId, int days) {
                log.info("Getting usage for userId {} over past {} days ", userId, days);

                final List<DeviceDto> devicesDto = deviceClient.getAllDevicesForUser(userId);

                final List<Device> devices = new ArrayList<>();

                for (DeviceDto deviceDto : devicesDto) {
                        devices.add(Device.builder()
                                        .deviceId(deviceDto.deviceId())
                                        .name(deviceDto.name())
                                        .type(deviceDto.type())
                                        .location(deviceDto.location())
                                        .userId(deviceDto.userId())
                                        .build());
                }
                if (devices == null || devices.isEmpty()) {
                        return UsageDto.builder()
                                        .userId(userId)
                                        .devices(null)
                                        .build();
                }
                List<String> deviceIdStrings = devices.stream()
                                .map(Device::getDeviceId)
                                .filter(Objects::nonNull)
                                .map(String::valueOf)
                                .toList();

                final Instant now = Instant.now();
                final Instant start = now.minusSeconds((long) days * 24 * 60 * 60);

                // build deivce filter
                // Input
                // ["101", "102", "103"]
                // Map — ID တစ်ခုစီကို condition ပြောင်းခြင်း
                // "deviceId" == "101"
                // "deviceId" == "102"
                // "deviceId" == "103"
                // Joining — or နဲ့ ဆက်ခြင်း
                // "deviceId" == "101" or "deviceId" == "102" or "deviceId" == "103"
                final String deviceFilter = deviceIdStrings.stream()
                                .map(idStr -> String.format("\"deviceId\" == \"%s\"", idStr))
                                .collect(Collectors.joining(" or "));

                String fluxQuery = String.format("""
                                from(bucket: "%s")
                                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                                  |> filter(fn: (r) => r["_measurement"] == "energy-usage")
                                  |> filter(fn: (r) => r["_field"] == "energyConsumed")
                                  |> filter(fn: (r) => %s)
                                  |> group(columns: ["deviceId"])
                                  |> sum(column: "_value")
                                """,
                                influxBucket,
                                start.toString(),
                                now.toString(),
                                deviceFilter);
                final Map<Long, Double> aggregatedMap = new HashMap<>();

                try {
                        QueryApi queryApi = influxDBClient.getQueryApi();
                        List<FluxTable> tables = queryApi.query(fluxQuery, influxOrg);

                        for (FluxTable table : tables) {
                                for (FluxRecord record : table.getRecords()) {

                                        Object deviceIdValue = record.getValueByKey("deviceId");

                                        String deviceIdStr = deviceIdValue == null ? null : deviceIdValue.toString();

                                        if (deviceIdStr == null)
                                                continue;

                                        Object value = record.getValueByKey("_value");

                                        Double energyConsumed = value instanceof Number
                                                        ? ((Number) value).doubleValue()
                                                        : 0.0;

                                        if (deviceIdValue == null) {
                                                log.warn("Skipping record because deviceId is null");
                                                continue;
                                        }

                                        if (!(energyConsumed instanceof Number)) {
                                                log.warn("Skipping record because energyConsumed is missing or invalid");
                                                continue;
                                        }

                                        Long deviceId;
                                        try {
                                                deviceId = Long.valueOf(deviceIdStr);
                                                aggregatedMap
                                                                .put(deviceId, aggregatedMap
                                                                                .getOrDefault(deviceId, 0.0)
                                                                                + energyConsumed);

                                        } catch (NumberFormatException e) {
                                                log.warn("Failed to parse deviceId from flux record : {}",
                                                                deviceIdStr);
                                                continue;
                                        }

                                }
                        }
                } catch (Exception e) {
                        log.error("Failed to query InfluxDB for user {} usage over {} days: {}",
                                        userId, days, e.getMessage());

                        // Set aggregated consumption to 0.0 on error
                        devices.forEach(device -> device.setEnergyConsumed(0.0));

                        return UsageDto.builder()
                                        .userId(userId)
                                        .devices(null)
                                        .build();
                }

                // populate agggregated energy cosumed per device
                for (Device device : devices) {
                        if (device == null || device.getDeviceId() == null)
                                continue;
                        device.setEnergyConsumed(aggregatedMap.getOrDefault(device.getDeviceId(), 0.0));

                }

                log.info("Aggregated energyt consumption for userID {} : {}", userId, aggregatedMap);
                final List<DeviceDto> resultDevices = devices.stream()
                                .map(d -> DeviceDto.builder()
                                                .deviceId(d.getDeviceId())
                                                .name(d.getName())
                                                .type(d.getType())
                                                .location(d.getLocation())
                                                .userId(d.getUserId())
                                                .energyConsumed(d.getEnergyConsumed())
                                                .build())
                                .collect(Collectors.toList());

                return UsageDto.builder()
                                .userId(userId)
                                .devices(resultDevices)
                                .build();
        }

        /**
         * Returns per-device energy consumption time series bucketed by hour or day.
         *
         * <p>
         * {@code interval} is {@code "hour"} or {@code "day"}. Hourly series are capped
         * at 7 days (168 buckets/device); daily series at 90 days — beyond that the
         * response grows large enough to hurt the demo UX.
         */
        public UsageSeriesDto getUsageSeries(Long userId, int days, String interval) {
                final String every;
                final int effectiveDays;
                if ("hour".equalsIgnoreCase(interval)) {
                        every = "1h";
                        effectiveDays = Math.min(days, 7);
                } else {
                        every = "1d";
                        effectiveDays = Math.min(days, 90);
                }

                final List<DeviceDto> devicesDto = deviceClient.getAllDevicesForUser(userId);
                if (devicesDto == null || devicesDto.isEmpty()) {
                        return UsageSeriesDto.builder()
                                        .userId(userId)
                                        .interval(interval.toLowerCase())
                                        .days(effectiveDays)
                                        .devices(List.of())
                                        .build();
                }

                final List<Long> deviceIds = devicesDto.stream()
                                .map(DeviceDto::deviceId)
                                .filter(Objects::nonNull)
                                .toList();
                if (deviceIds.isEmpty()) {
                        return UsageSeriesDto.builder()
                                        .userId(userId)
                                        .interval(interval.toLowerCase())
                                        .days(effectiveDays)
                                        .devices(List.of())
                                        .build();
                }

                final Instant now = Instant.now();
                final Instant start = now.minus(effectiveDays, ChronoUnit.DAYS);
                final String deviceFilter = buildDeviceFilter(deviceIds);

                final String fluxQuery = String.format("""
                                from(bucket: "%s")
                                  |> range(start: time(v: "%s"), stop: time(v: "%s"))
                                  |> filter(fn: (r) => r["_measurement"] == "energy-usage")
                                  |> filter(fn: (r) => r["_field"] == "energyConsumed")
                                  |> filter(fn: (r) => %s)
                                  |> group(columns: ["deviceId"])
                                  |> aggregateWindow(every: %s, fn: sum, createEmpty: false, timeSrc: "_start")
                                """,
                                influxBucket, start.toString(), now.toString(), deviceFilter, every);

                // deviceId -> (bucket time -> summed energy), LinkedHashMap keeps time order
                final Map<Long, Map<Instant, Double>> seriesMap = new HashMap<>();

                try {
                        final QueryApi queryApi = influxDBClient.getQueryApi();
                        final List<FluxTable> tables = queryApi.query(fluxQuery, influxOrg);

                        for (FluxTable table : tables) {
                                for (FluxRecord record : table.getRecords()) {
                                        final Long deviceId = parseDeviceId(
                                                        record.getValueByKey("deviceId") == null
                                                                        ? null
                                                                        : record.getValueByKey("deviceId").toString());
                                        final Object value = record.getValueByKey("_value");
                                        if (deviceId == null || !(value instanceof Number)) {
                                                continue;
                                        }
                                        final Instant bucketTime = record.getTime();
                                        if (bucketTime == null) {
                                                continue;
                                        }
                                        seriesMap.computeIfAbsent(deviceId, k -> new java.util.TreeMap<>())
                                                        .put(bucketTime,
                                                                        ((Number) value).doubleValue());
                                }
                        }
                } catch (Exception e) {
                        log.error("Failed to query InfluxDB series for user {} over {} days ({}): {}",
                                        userId, effectiveDays, every, e.getMessage());
                        return UsageSeriesDto.builder()
                                        .userId(userId)
                                        .interval(every.equals("1h") ? "hour" : "day")
                                        .days(effectiveDays)
                                        .devices(List.of())
                                        .build();
                }

                final List<DeviceSeriesDto> devices = devicesDto.stream()
                                .map(d -> DeviceSeriesDto.builder()
                                                .deviceId(d.deviceId())
                                                .name(d.name())
                                                .type(d.type())
                                                .location(d.location())
                                                .points(seriesMap
                                                                .getOrDefault(d.deviceId(), Map.of())
                                                                .entrySet().stream()
                                                                .map(e -> EnergyPointDto.builder()
                                                                                .time(e.getKey())
                                                                                .energyConsumed(e.getValue())
                                                                                .build())
                                                                .toList())
                                                .build())
                                .toList();

                return UsageSeriesDto.builder()
                                .userId(userId)
                                .interval(every.equals("1h") ? "hour" : "day")
                                .days(effectiveDays)
                                .devices(devices)
                                .build();
        }

        /**
         * Returns how much historical data actually exists for a user.
         *
         * <p>
         * {@code maxDays} is the calendar span from the first day with a reading
         * through today (inclusive) - the longest reporting window that can be
         * served. {@code daysWithData} counts only the distinct calendar days that
         * contain at least one reading, so gaps in coverage stay visible instead of
         * being mistaken for zero consumption.
         */
        public UsageCoverageDto getCoverage(Long userId) {
                log.info("Computing usage coverage for userId {}", userId);

                final List<DeviceDto> devicesDto = deviceClient.getAllDevicesForUser(userId);
                final List<Long> deviceIds = devicesDto == null
                                ? List.of()
                                : devicesDto.stream()
                                                .map(DeviceDto::deviceId)
                                                .filter(Objects::nonNull)
                                                .distinct()
                                                .toList();

                if (deviceIds.isEmpty()) {
                        return UsageCoverageDto.builder()
                                        .userId(userId)
                                        .deviceCount(0)
                                        .devicesWithNoData(List.of())
                                        .hasAnyData(false)
                                        .maxDays(0)
                                        .daysWithData(0)
                                        .build();
                }

                final String deviceFilter = buildDeviceFilter(deviceIds);
                final QueryApi queryApi = influxDBClient.getQueryApi();

                final String dailyWindowsQuery = String.format("""
                                from(bucket: "%s")
                                  |> range(start: 0)
                                  |> filter(fn: (r) => r["_measurement"] == "energy-usage")
                                  |> filter(fn: (r) => r["_field"] == "energyConsumed")
                                  |> filter(fn: (r) => %s)
                                  |> group()
                                  |> aggregateWindow(every: 1d, fn: count, createEmpty: false)
                                  |> group()
                                  |> sort(columns: ["_start"], desc: false)
                                """,
                                influxBucket, deviceFilter);

                final String devicePresenceQuery = String.format("""
                                from(bucket: "%s")
                                  |> range(start: 0)
                                  |> filter(fn: (r) => r["_measurement"] == "energy-usage")
                                  |> filter(fn: (r) => r["_field"] == "energyConsumed")
                                  |> filter(fn: (r) => %s)
                                  |> group(columns: ["deviceId"])
                                  |> count(column: "_value")
                                """,
                                influxBucket, deviceFilter);

                final List<FluxRecord> windowRecords = queryApi.query(dailyWindowsQuery, influxOrg)
                                .stream()
                                .flatMap(table -> table.getRecords().stream())
                                .toList();

                if (windowRecords.isEmpty()) {
                        log.info("No usage records found for userId {}", userId);
                        return UsageCoverageDto.builder()
                                        .userId(userId)
                                        .deviceCount(deviceIds.size())
                                        .devicesWithNoData(deviceIds)
                                        .hasAnyData(false)
                                        .maxDays(0)
                                        .daysWithData(0)
                                        .build();
                }

                final LocalDate firstDataDay = toUtcDate(windowRecords.get(0).getTime());
                final LocalDate lastDataDay = toUtcDate(
                                windowRecords.get(windowRecords.size() - 1).getTime());
                final int daysWithData = windowRecords.size();
                final int maxDays = (int) ChronoUnit.DAYS.between(firstDataDay,
                                LocalDate.now(ZoneOffset.UTC)) + 1;

                final Set<Long> devicesWithRecords = queryApi.query(devicePresenceQuery, influxOrg)
                                .stream()
                                .flatMap(table -> table.getRecords().stream())
                                .map(record -> record.getValueByKey("deviceId"))
                                .filter(Objects::nonNull)
                                .map(value -> parseDeviceId(value.toString()))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(LinkedHashSet::new));

                final List<Long> devicesWithNoData = deviceIds.stream()
                                .filter(id -> !devicesWithRecords.contains(id))
                                .sorted()
                                .toList();

                log.info("Coverage for userId {}: firstDay={}, lastDay={}, daysWithData={}, "
                                + "maxDays={}, devicesWithNoData={}",
                                userId, firstDataDay, lastDataDay, daysWithData, maxDays,
                                devicesWithNoData);

                return UsageCoverageDto.builder()
                                .userId(userId)
                                .firstDataDay(firstDataDay)
                                .lastDataDay(lastDataDay)
                                .maxDays(maxDays)
                                .daysWithData(daysWithData)
                                .deviceCount(deviceIds.size())
                                .devicesWithNoData(devicesWithNoData)
                                .hasAnyData(true)
                                .build();
        }

        private static String buildDeviceFilter(List<Long> deviceIds) {
                return deviceIds.stream()
                                .map(id -> String.format("\"deviceId\" == \"%s\"", id))
                                .collect(Collectors.joining(" or "));
        }

        private static LocalDate toUtcDate(Instant instant) {
                return instant == null ? null : instant.atZone(ZoneOffset.UTC).toLocalDate();
        }

        private static Long parseDeviceId(String value) {
                try {
                        return Long.valueOf(value);
                } catch (NumberFormatException e) {
                        return null;
                }
        }
}
