package com.energytracker.usageservice.dto;

import java.util.List;

import lombok.Builder;

@Builder
public record UsageSeriesDto(
                Long userId,
                String interval,
                Integer days,
                List<DeviceSeriesDto> devices) {

}
