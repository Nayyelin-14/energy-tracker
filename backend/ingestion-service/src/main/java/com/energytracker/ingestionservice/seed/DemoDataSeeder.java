package com.energytracker.ingestionservice.seed;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.energytracker.ingestionservice.client.DeviceClient;
import com.energytracker.ingestionservice.dto.DeviceDto;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;

import lombok.extern.slf4j.Slf4j;

/**
 * Seeds the demo playground on startup:
 * 1. Creates a demo user via user-service (idempotent — skips if email exists).
 * 2. Creates demo devices via device-service (idempotent — skips if devices exist).
 * 3. Writes 7 days of historical energy readings to InfluxDB (idempotent —
 *    deletes existing readings for the demo devices before re-seeding).
 */
@Slf4j
@Component
public class DemoDataSeeder implements CommandLineRunner {

        private static final List<DEMO_DEVICE> DEMO_DEVICES = List.of(
                        new DEMO_DEVICE("Living Room Meter", "THERMOSTAT", "Living Room"),
                        new DEMO_DEVICE("Kitchen AC", "LIGHT", "Kitchen"),
                        new DEMO_DEVICE("Solar Panel", "CAMERA", "Roof"));

        private record DEMO_DEVICE(String name, String type, String location) {
        }

        private final RestTemplate restTemplate = new RestTemplate();
        private final DeviceClient deviceClient;
        private final InfluxDBClient influxDBClient;

        @Value("${demo.seed.enabled:true}")
        private boolean seedEnabled;

        @Value("${demo.seed.email}")
        private String demoEmail;

        @Value("${demo.seed.password}")
        private String demoPassword;

        @Value("${demo.seed.name}")
        private String demoName;

        @Value("${demo.seed.threshold}")
        private double demoThreshold;

        @Value("${demo.seed.alerting}")
        private boolean demoAlerting;

        @Value("${user.service.url}")
        private String userServiceUrl;

        @Value("${device.service.url}")
        private String deviceServiceUrl;

        @Value("${influx.org}")
        private String influxOrg;

        @Value("${influx.bucket}")
        private String influxBucket;

        public DemoDataSeeder(DeviceClient deviceClient, InfluxDBClient influxDBClient) {
                this.deviceClient = deviceClient;
                this.influxDBClient = influxDBClient;
        }

        @Override
        public void run(String... args) {
                if (!seedEnabled) {
                        log.info("Demo seeding is disabled");
                        return;
                }

                try {
                        log.info("Starting demo data seed...");

                        final Long demoUserId = ensureDemoUser();
                        if (demoUserId == null) {
                                log.error("Failed to create demo user, aborting seed");
                                return;
                        }
                        log.info("Demo user ready: id={}", demoUserId);

                        final List<Long> deviceIds = ensureDemoDevices(demoUserId);
                        log.info("Demo devices ready: {}", deviceIds);

                        seedInfluxData(deviceIds);
                        log.info("Demo data seed complete. userId={}, devices={}", demoUserId, deviceIds);

                } catch (Exception e) {
                        log.error("Demo data seed failed (will retry on next restart)", e);
                }
        }

        private Long ensureDemoUser() {
                // Try to register — if email already exists, 409 means the demo user is already seeded
                final Map<String, Object> body = Map.of(
                                "email", demoEmail,
                                "password", demoPassword,
                                "confirmPassword", demoPassword,
                                "name", demoName,
                                "alerting", demoAlerting,
                                "energyAlertingThreshold", demoThreshold,
                                "demo", true);

                try {
                        final HttpHeaders headers = new HttpHeaders();
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        final ResponseEntity<Map> response = restTemplate.postForEntity(
                                        userServiceUrl + "/register",
                                        new HttpEntity<>(body, headers),
                                        Map.class);

                        final Object userId = response.getBody().get("userId");
                        log.info("Created demo user: {}", userId);
                        return userId != null ? Long.valueOf(userId.toString()) : null;

                } catch (HttpClientErrorException.Conflict e) {
                        // User already exists — look it up by email and heal the demo flag
                        // (users created before the demo flag existed have demo=false)
                        log.info("Demo user already exists, looking up ID by email...");
                        return lookupDemoUserId();
                } catch (Exception e) {
                        log.error("Failed to create demo user", e);
                        return null;
                }
        }

        private Long lookupDemoUserId() {
                try {
                        final ResponseEntity<Map> response = restTemplate.getForEntity(
                                        userServiceUrl + "/email/" + demoEmail, Map.class);
                        final Map<String, Object> body = response.getBody();
                        if (body == null || body.get("userId") == null) {
                                log.error("Demo user lookup by email returned no userId");
                                return null;
                        }

                        final Long userId = Long.valueOf(body.get("userId").toString());

                        // Heal the demo flag for users seeded before V6 / this fix
                        if (!Boolean.TRUE.equals(body.get("demo"))) {
                                log.info("Demo user {} has demo=false, setting flag...", userId);
                                final HttpHeaders headers = new HttpHeaders();
                                headers.setContentType(MediaType.APPLICATION_JSON);
                                restTemplate.put(userServiceUrl + "/" + userId,
                                                new HttpEntity<>(Map.of("demo", true), headers));
                        }
                        return userId;
                } catch (Exception e) {
                        log.error("Failed to look up demo user by email {}", demoEmail, e);
                        return null;
                }
        }

        private List<Long> ensureDemoDevices(Long demoUserId) {
                final List<DeviceDto> existing = deviceClient.getAllDevicesForUser(demoUserId);
                if (existing != null && !existing.isEmpty()) {
                        log.info("Demo devices already exist ({}), skipping creation", existing.size());
                        return existing.stream().map(DeviceDto::deviceId).toList();
                }

                final List<Long> ids = new java.util.ArrayList<>();
                for (DEMO_DEVICE spec : DEMO_DEVICES) {
                        try {
                                final DeviceDto created = createDevice(spec, demoUserId);
                                if (created != null && created.deviceId() != null) {
                                        ids.add(created.deviceId());
                                }
                        } catch (Exception e) {
                                log.error("Failed to create demo device: {}", spec.name(), e);
                        }
                }
                return ids;
        }

        private DeviceDto createDevice(DEMO_DEVICE spec, Long userId) {
                final Map<String, Object> body = Map.of(
                                "name", spec.name(),
                                "type", spec.type(),
                                "location", spec.location(),
                                "userId", userId);

                final HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                final ResponseEntity<DeviceDto> response = restTemplate.postForEntity(
                                deviceServiceUrl + "/create",
                                new HttpEntity<>(body, headers),
                                DeviceDto.class);

                return response.getBody();
        }

        private void seedInfluxData(List<Long> deviceIds) {
                if (deviceIds.isEmpty()) {
                        log.warn("No demo devices to seed InfluxDB data for");
                        return;
                }

                // Idempotent: delete existing readings for demo devices before re-seeding
                deleteExistingReadings(deviceIds);

                final Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);
                int totalPoints = 0;

                for (Long deviceId : deviceIds) {
                        for (int daysAgo = 7; daysAgo >= 1; daysAgo--) {
                                for (int hour = 0; hour < 24; hour++) {
                                        final Instant timestamp = now
                                                        .minus(daysAgo, ChronoUnit.DAYS)
                                                        .plus(hour, ChronoUnit.HOURS);

                                        final double energy = generateReading(hour);
                                        final Point point = Point.measurement("energy-usage")
                                                        .addTag("deviceId", String.valueOf(deviceId))
                                                        .addField("energyConsumed", energy)
                                                        .time(timestamp.toEpochMilli(), WritePrecision.MS);

                                        influxDBClient.getWriteApiBlocking()
                                                        .writePoint(influxBucket, influxOrg, point);
                                        totalPoints++;
                                }
                        }
                }
                log.info("Seeded {} InfluxDB readings across {} devices", totalPoints, deviceIds.size());
        }

        private void deleteExistingReadings(List<Long> deviceIds) {
                try {
                        final String deviceFilter = deviceIds.stream()
                                        .map(id -> String.format("deviceId == \"%s\"", id))
                                        .collect(java.util.stream.Collectors.joining(" OR "));

                        // InfluxDB delete predicates support only measurement + tag equality
                        // (no _field filter). The measurement has a single field, so
                        // filtering by measurement + deviceId is sufficient.
                        final String predicate = String.format(
                                        "_measurement == \"energy-usage\" AND (%s)",
                                        deviceFilter);

                        influxDBClient.getDeleteApi().delete(
                                        java.time.OffsetDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC),
                                        java.time.OffsetDateTime.now(),
                                        predicate,
                                        influxBucket,
                                        influxOrg);
                        log.info("Cleared existing InfluxDB readings for demo devices");
                } catch (Exception e) {
                        log.warn("Could not clear existing InfluxDB readings (may be first seed): {}",
                                        e.getMessage());
                }
        }

        /**
         * Generates a realistic daily pattern:
         * - Night (0-6): low ~2 kWh
         * - Morning (7-9): medium ~4 kWh
         * - Day (10-16): medium-high ~5 kWh
         * - Evening peak (17-21): high ~8 kWh
         * - Late night (22-23): low ~3 kWh
         * Plus random ±1 kWh variation.
         */
        private double generateReading(int hour) {
                final double base;
                if (hour <= 6) {
                        base = 2.0;
                } else if (hour <= 9) {
                        base = 4.0;
                } else if (hour <= 16) {
                        base = 5.0;
                } else if (hour <= 21) {
                        base = 8.0;
                } else {
                        base = 3.0;
                }
                final double variation = ThreadLocalRandom.current().nextDouble(-1.0, 1.0);
                return Math.max(0.5, Math.round((base + variation) * 100.0) / 100.0);
        }
}
