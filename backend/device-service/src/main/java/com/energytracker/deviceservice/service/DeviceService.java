package com.energytracker.deviceservice.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.energytracker.deviceservice.dto.DeviceDto;
import com.energytracker.deviceservice.entity.Device;
import com.energytracker.deviceservice.exception.ResourceNotFoundException;
import com.energytracker.deviceservice.repository.DeviceRepository;
import com.energytracker.deviceservice.util.UniqueIdGenerator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceService {

        /**
         * Bounded retry when a generated device id collides with an existing row
         * (astronomically unlikely with 63 random bits, but never let it silently
         * fail a device creation).
         */
        private static final int MAX_INSERT_ATTEMPTS = 3;

        private final DeviceRepository deviceRepository;

        public DeviceDto getDeviceById(Long deviceId) {
                if (deviceId == null) {
                        throw new ResourceNotFoundException(
                                        "Device ID must be provided");
                }
                Device device = deviceRepository.findById(
                                deviceId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Device not found with id " + deviceId));
                return mapToDto(device);
        }

        public List<DeviceDto> getAllDevices() {
                return deviceRepository.findAll()
                                .stream()
                                .map(this::mapToDto)
                                .toList();
        }

        public DeviceDto createDevice(DeviceDto input) {
                if (input == null
                                || input.getName() == null
                                || input.getType() == null
                                || input.getLocation() == null
                                || input.getUserId() == null) {
                        throw new IllegalArgumentException(
                                        "Name, type, and userId must be provided");
                }
                Device device = Device.builder()
                                .name(input.getName())
                                .type(input.getType())
                                .location(input.getLocation())
                                .userId(input.getUserId())
                                .build();

                return mapToDto(saveNewDevice(device));
        }

        public DeviceDto updateDevice(Long deviceId, DeviceDto input) {
                if (input == null
                                || (input.getName() == null
                                                && input.getType() == null
                                                && input.getLocation() == null)) {
                        throw new IllegalArgumentException(
                                        "At least one field must be provided for update");
                }
                Device existingDevice = deviceRepository.findById(deviceId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Device not found with id " + deviceId));

                if (input.getName() != null) {
                        existingDevice.setName(input.getName());
                }

                if (input.getType() != null) {
                        existingDevice.setType(input.getType());
                }

                if (input.getLocation() != null) {
                        existingDevice.setLocation(input.getLocation());
                }

                existingDevice.setUpdatedAt(LocalDateTime.now());

                Device updatedDevice = deviceRepository.save(existingDevice);

                return mapToDto(updatedDevice);
        }

        public void deleteDevice(Long deviceId) {
                if (deviceId == null) {
                        throw new IllegalArgumentException("Device ID must be provided");
                }

                Device device = deviceRepository.findById(deviceId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Device not found with id " + deviceId));

                deviceRepository.delete(device);
        }

        private Device saveNewDevice(Device device) {
                for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
                        // With an app-assigned id, JPA save() uses merge(), which would
                        // silently OVERWRITE an existing row on an id collision instead of
                        // failing - so guard against the known id first.
                        if (deviceRepository.existsById(device.getDeviceId())) {
                                if (attempt >= MAX_INSERT_ATTEMPTS) {
                                        throw new DataIntegrityViolationException(
                                                        "Generated device id still collides after "
                                                                        + MAX_INSERT_ATTEMPTS + " attempts");
                                }
                                log.warn("Duplicate device id {} generated, retrying (attempt {}/{})",
                                                device.getDeviceId(), attempt, MAX_INSERT_ATTEMPTS);
                                device.setDeviceId(UniqueIdGenerator.nextId());
                                continue;
                        }
                        try {
                                // saveAndFlush surfaces constraint violations (FK, duplicate
                                // key) inside this call instead of at transaction commit
                                return deviceRepository.saveAndFlush(device);
                        } catch (DataIntegrityViolationException e) {
                                if (attempt >= MAX_INSERT_ATTEMPTS || !isDuplicateId(e, device.getDeviceId())) {
                                        // Not an id collision (e.g. FK violation on user_id) or
                                        // retries exhausted: surface the original error untouched.
                                        throw e;
                                }
                                log.warn("Duplicate device id {} generated, retrying (attempt {}/{})",
                                                device.getDeviceId(), attempt, MAX_INSERT_ATTEMPTS);
                                device.setDeviceId(UniqueIdGenerator.nextId());
                        }
                }
                throw new IllegalStateException("unreachable: saveNewDevice exhausted its retry loop");
        }

        /**
         * Matches MySQL's duplicate-key message for this exact id value so that
         * unrelated constraint violations (FK on device.user_id) are rethrown.
         */
        private static boolean isDuplicateId(Throwable ex, Long id) {
                if (id == null) {
                        return false;
                }
                final String marker = "Duplicate entry '" + id + "'";
                for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                        final String message = cause.getMessage();
                        if (message != null && message.contains(marker)) {
                                return true;
                        }
                }
                return false;
        }

        public List<DeviceDto> getAllDevicesByUserId(Long userId) {
                if (userId == null) {
                        throw new IllegalArgumentException("User ID must be provided");
                }

                List<Device> devices = deviceRepository.findAllByUserId(userId);

                return devices.stream()
                                .map(this::mapToDto)
                                .toList();
        }

        private DeviceDto mapToDto(Device device) {
                return DeviceDto.builder()
                                .deviceId(device.getDeviceId())
                                .name(device.getName())
                                .type(device.getType())
                                .location(device.getLocation())
                                .userId(device.getUserId())
                                .createdAt(device.getCreatedAt())
                                .updatedAt(device.getUpdatedAt())
                                .build();
        }
}
