package com.energytracker.usageservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.energytracker.kafka.event.AlertingEvent;
import com.energytracker.usageservice.client.DeviceClient;
import com.energytracker.usageservice.client.UserClient;
import com.energytracker.usageservice.dto.DeviceDto;
import com.energytracker.usageservice.dto.UsageCoverageDto;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;

class UsageServiceCoverageTest {

        private InfluxDBClient influxDBClient;
        private DeviceClient deviceClient;
        private QueryApi queryApi;
        private UsageService usageService;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUp() {
                influxDBClient = mock(InfluxDBClient.class);
                deviceClient = mock(DeviceClient.class);
                UserClient userClient = mock(UserClient.class);
                KafkaTemplate<String, AlertingEvent> kafkaTemplate = mock(KafkaTemplate.class);
                queryApi = mock(QueryApi.class);

                when(influxDBClient.getQueryApi()).thenReturn(queryApi);

                usageService = new UsageService(influxDBClient, deviceClient, userClient, kafkaTemplate);
                ReflectionTestUtils.setField(usageService, "influxOrg", "nay");
                ReflectionTestUtils.setField(usageService, "influxBucket", "usage-bucket");
        }

        @Test
        void reportsNoDataWhenUserHasNoDevices() {
                when(deviceClient.getAllDevicesForUser(1L)).thenReturn(List.of());

                UsageCoverageDto coverage = usageService.getCoverage(1L);

                assertThat(coverage.hasAnyData()).isFalse();
                assertThat(coverage.deviceCount()).isZero();
                assertThat(coverage.devicesWithNoData()).isEmpty();
                assertThat(coverage.maxDays()).isZero();
                verifyNoInteractions(queryApi);
        }

        @Test
        void reportsNoDataWhenDevicesExistButNoRecordsAreStored() {
                when(deviceClient.getAllDevicesForUser(1L))
                                .thenReturn(List.of(device(1L, "Fridge"), device(2L, "TV")));
                when(queryApi.query(anyString(), anyString())).thenReturn(List.of());

                UsageCoverageDto coverage = usageService.getCoverage(1L);

                assertThat(coverage.hasAnyData()).isFalse();
                assertThat(coverage.deviceCount()).isEqualTo(2);
                assertThat(coverage.devicesWithNoData()).containsExactly(1L, 2L);
        }

        @Test
        void distinguishesCalendarSpanFromDaysWithActualReadings() {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                LocalDate firstDay = today.minusDays(4);
                LocalDate midDay = today.minusDays(2);

                when(deviceClient.getAllDevicesForUser(1L)).thenReturn(List.of(
                                device(1L, "Fridge"),
                                device(2L, "TV"),
                                device(3L, "Heater")));

                List<FluxTable> dayTables = List.of(table(
                                timeRecord(firstDay),
                                timeRecord(midDay),
                                timeRecord(today)));
                List<FluxTable> deviceTables = List.of(table(deviceRecord(1L)));
                when(queryApi.query(anyString(), anyString()))
                                .thenReturn(dayTables)
                                .thenReturn(deviceTables);

                UsageCoverageDto coverage = usageService.getCoverage(1L);

                assertThat(coverage.hasAnyData()).isTrue();
                assertThat(coverage.firstDataDay()).isEqualTo(firstDay);
                assertThat(coverage.lastDataDay()).isEqualTo(today);
                assertThat(coverage.daysWithData()).isEqualTo(3);
                assertThat(coverage.maxDays()).isEqualTo(5);
                assertThat(coverage.deviceCount()).isEqualTo(3);
                assertThat(coverage.devicesWithNoData()).containsExactly(2L, 3L);
        }

        @Test
        void coverageNeverCountsCalendarSpanAsReadings() {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                LocalDate firstDay = today.minusDays(29);

                when(deviceClient.getAllDevicesForUser(1L)).thenReturn(List.of(device(1L, "Fridge")));

                List<FluxTable> dayTables = List.of(table(timeRecord(firstDay), timeRecord(today)));
                List<FluxTable> deviceTables = List.of(table(deviceRecord(1L)));
                when(queryApi.query(anyString(), anyString()))
                                .thenReturn(dayTables)
                                .thenReturn(deviceTables);

                UsageCoverageDto coverage = usageService.getCoverage(1L);

                assertThat(coverage.maxDays()).isEqualTo(30);
                assertThat(coverage.daysWithData()).isEqualTo(2);
        }

        private static DeviceDto device(Long id, String name) {
                return DeviceDto.builder()
                                .deviceId(id)
                                .name(name)
                                .type("APPLIANCE")
                                .location("Kitchen")
                                .userId(1L)
                                .build();
        }

        private static FluxRecord timeRecord(LocalDate day) {
                FluxRecord record = new FluxRecord(0);
                record.getValues().put("_time", day.atTime(12, 0).toInstant(ZoneOffset.UTC));
                return record;
        }

        private static FluxRecord deviceRecord(Long deviceId) {
                FluxRecord record = new FluxRecord(0);
                record.getValues().put("deviceId", String.valueOf(deviceId));
                record.getValues().put("_value", 1L);
                return record;
        }

        @SuppressWarnings("unchecked")
        private static FluxTable table(FluxRecord... records) {
                FluxTable table = mock(FluxTable.class);
                when(table.getRecords()).thenReturn(List.of(records));
                return table;
        }
}
