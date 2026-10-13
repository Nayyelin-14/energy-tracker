package com.energytracker.usageservice.dto;

import java.util.List;

import lombok.Builder;

@Builder
public record DeviceSeriesDto(
                Long deviceId,
                String name,
                String type,
                String location,
                List<EnergyPointDto> points) {

}
