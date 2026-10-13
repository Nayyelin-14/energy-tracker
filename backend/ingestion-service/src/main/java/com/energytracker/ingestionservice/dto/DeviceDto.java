package com.energytracker.ingestionservice.dto;

import lombok.Builder;

/**
 * Projection of device-service's device response.
 */
@Builder
public record DeviceDto(
                Long deviceId,
                String name,
                String type,
                String location,
                Long userId) {
}
