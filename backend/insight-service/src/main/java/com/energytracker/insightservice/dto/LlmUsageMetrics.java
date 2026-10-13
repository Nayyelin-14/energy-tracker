package com.energytracker.insightservice.dto;

import lombok.Builder;

@Builder
public record LlmUsageMetrics(
                String provider,
                String model,
                int inputTokens,
                int outputTokens,
                int totalTokens,
                long generationTimeMs,
                long totalRequestTimeMs,
                CacheStatus cacheStatus) {

        public enum CacheStatus {
                HIT, MISS
        }
}
