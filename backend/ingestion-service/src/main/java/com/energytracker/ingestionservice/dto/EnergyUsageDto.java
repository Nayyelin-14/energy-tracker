package com.energytracker.ingestionservice.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;

@Builder 
public record EnergyUsageDto(
                @NotNull(message = "deviceId is required") Long deviceId,

                @PositiveOrZero(message = "energyConsumed must not be negative") double energyConsumed,

                @NotNull(message = "timestamp is required")
                @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {
}
