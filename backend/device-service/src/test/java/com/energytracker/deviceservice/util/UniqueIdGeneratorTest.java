package com.energytracker.deviceservice.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.energytracker.deviceservice.entity.Device;
import com.energytracker.deviceservice.model.DeviceType;

class UniqueIdGeneratorTest {

        @Test
        void nextIdIsAlwaysPositive() {
                for (int i = 0; i < 10_000; i++) {
                        assertThat(UniqueIdGenerator.nextId()).isPositive();
                }
        }

        @Test
        void nextIdsDoNotCollide() {
                Set<Long> ids = new HashSet<>();
                for (int i = 0; i < 100_000; i++) {
                        ids.add(UniqueIdGenerator.nextId());
                }
                assertThat(ids).hasSize(100_000);
        }

        @Test
        void deviceGetsDefaultIdFromNoArgsConstructor() {
                Device device = new Device();
                assertThat(device.getDeviceId()).isNotNull().isPositive();
        }

        @Test
        void deviceGetsDefaultIdFromBuilderWhenIdNotSet() {
                Device device = Device.builder()
                                .name("Lamp")
                                .type(DeviceType.LIGHT)
                                .location("Room")
                                .userId(123L)
                                .build();
                assertThat(device.getDeviceId()).isNotNull().isPositive();
        }
}
