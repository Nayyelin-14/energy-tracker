package com.energytracker.insightservice.dto;

import java.util.List;

import lombok.Builder;

@Builder
public record ProviderCatalogDto(
                String defaultProvider,
                String defaultModel,
                Integer maxDays,
                Integer defaultDays,
                List<ProviderInfo> providers) {

        @Builder
        public record ProviderInfo(
                        String id,
                        String displayName,
                        List<ModelInfo> models) {
        }

        @Builder
        public record ModelInfo(
                        String id,
                        String displayName) {
        }
}
