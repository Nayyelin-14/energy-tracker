package com.energytracker.deviceservice.dto;

import java.time.LocalDateTime;

import com.energytracker.deviceservice.model.DeviceType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

        @NotBlank(message = "Name is required")
        private String name;

        @NotNull(message = "Type is required")
        private DeviceType type;

        @NotBlank(message = "Location is required")
        private String location;

        @NotNull(message = "userId is required")
        private Long userId;

        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
}
