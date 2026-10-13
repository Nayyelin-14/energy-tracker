package com.energytracker.usageservice.dto;

import java.time.Instant;

import lombok.Builder;

@Builder
public record EnergyPointDto(
                Instant time,
                Double energyConsumed) {

}
