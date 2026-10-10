package com.energytracker.deviceservice.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.energytracker.deviceservice.dto.DeviceDto;
import com.energytracker.deviceservice.entity.Device;
import com.energytracker.deviceservice.exception.ResourceNotFoundException;
import com.energytracker.deviceservice.repository.DeviceRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DeviceService {

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

                return mapToDto(deviceRepository.save(device));
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

                return DeviceDto.builder()
                                .deviceId(updatedDevice.getDeviceId())
                                .name(updatedDevice.getName())
                                .type(updatedDevice.getType())
                                .location(updatedDevice.getLocation())
                                .createdAt(updatedDevice.getCreatedAt())
                                .updatedAt(updatedDevice.getUpdatedAt())
                                .build();
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
