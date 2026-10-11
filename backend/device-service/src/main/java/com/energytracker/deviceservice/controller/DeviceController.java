package com.energytracker.deviceservice.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.energytracker.deviceservice.dto.DeviceDto;
import com.energytracker.deviceservice.service.DeviceService;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/api/v1/device")
@AllArgsConstructor
public class DeviceController {

        private static final String RESOURCE_PATH = "/api/v1/device/";

        private final DeviceService deviceService;

        @GetMapping
        public ResponseEntity<List<DeviceDto>> getAllDevices() {
                return ResponseEntity.ok(deviceService.getAllDevices());
        }

        @GetMapping("/{deviceId}")
        public ResponseEntity<DeviceDto> getDeviceById(@PathVariable Long deviceId) {
                return ResponseEntity.ok(deviceService.getDeviceById(deviceId));
        }

        @PostMapping("/create")
        public ResponseEntity<DeviceDto> createDevice(@Valid @RequestBody DeviceDto request) {
                DeviceDto createdDevice = deviceService.createDevice(request);
                URI location = URI.create(RESOURCE_PATH + createdDevice.getDeviceId());
                return ResponseEntity.created(location).body(createdDevice);
        }

        @PutMapping("/{deviceId}")
        public ResponseEntity<DeviceDto> updateDeviceInfo(
                        @PathVariable Long deviceId, @RequestBody DeviceDto deviceDto) {
                DeviceDto updatedDevice = deviceService.updateDevice(deviceId, deviceDto);
                return ResponseEntity.ok(updatedDevice);
        }

        @DeleteMapping("/{deviceId}")
        public ResponseEntity<Void> deleteDevice(@PathVariable Long deviceId) {
                deviceService.deleteDevice(deviceId);
                return ResponseEntity.noContent().build();
        }
}
