package com.energytracker.insightservice.dto;

import lombok.Builder;

@Builder
public record DeviceDto(
                Long deviceId,
                String name,
                String type,
                String location,
                double energyConsumed) {
}