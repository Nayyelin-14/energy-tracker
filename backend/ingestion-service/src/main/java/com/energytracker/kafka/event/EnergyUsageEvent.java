package com.energytracker.kafka.event;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Builder;

@Builder
public record EnergyUsageEvent(
                Long deviceId,
                double energyCosumed,

                @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {
}
