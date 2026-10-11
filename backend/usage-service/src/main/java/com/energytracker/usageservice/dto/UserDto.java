package com.energytracker.usageservice.dto;

import lombok.Builder;

@Builder
public record UserDto(
                Long userId,

                String email,

                String name,

                String address,
                boolean alerting,

                double energyAlertingThreshold) {
}
