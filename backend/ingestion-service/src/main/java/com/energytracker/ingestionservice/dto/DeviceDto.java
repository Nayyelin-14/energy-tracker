package com.energytracker.ingestionservice.dto;

import lombok.Builder;

/**
 * Projection of device-service's device list response; only the id is needed
 * here (unknown JSON fields are ignored by Spring's default ObjectMapper).
 */
@Builder
public record DeviceDto(
                Long deviceId) {
}
