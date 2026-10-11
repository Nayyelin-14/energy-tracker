package com.energytracker.usageservice.dto;

import lombok.Builder;

@Builder
public record DeviceDto(
                Long deviceId,
                String name,
                String type,
                String location,
                Long userId) {

}
