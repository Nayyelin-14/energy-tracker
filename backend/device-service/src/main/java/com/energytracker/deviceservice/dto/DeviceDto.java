package com.energytracker.deviceservice.dto;

import java.time.LocalDateTime;

import com.energytracker.deviceservice.model.DeviceType;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class DeviceDto {

        private Long deviceId;
        private String name;
        private DeviceType type;
        private String location;
        private String userId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
}
