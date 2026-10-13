package com.energytracker.insightservice.dto;

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

        public boolean hasData() {
                return Boolean.TRUE.equals(hasAnyData);
        }

        public int maxReportableDays() {
                return maxDays == null ? 0 : maxDays;
        }
}
