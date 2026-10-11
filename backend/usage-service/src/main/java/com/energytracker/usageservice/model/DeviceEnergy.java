package com.energytracker.usageservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeviceEnergy {
        private Long deviceId;
        private double energyConsumed;
        private Long userId;
}