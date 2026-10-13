package com.energytracker.usageservice.dto;

import java.time.LocalDate;
import java.util.List;

import lombok.Builder;

@Builder
public record UsageCoverageDto(
                Long userId,
                LocalDate firstDataDay,
                LocalDate lastDataDay,
                Integer maxDays,
                Integer daysWithData,
                Integer deviceCount,
                List<Long> devicesWithNoData,
                Boolean hasAnyData) {

}
