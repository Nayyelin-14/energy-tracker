package com.energytracker.insightservice.dto;

import lombok.Builder;

@Builder
public record InsightDto(
                Long userId,
                InsightType type,
                String tips,
                double energyUsage,
                Integer requestedDays,
                Integer effectiveDays,
                Boolean periodAdjusted,
                LlmUsageMetrics metrics) {
}
