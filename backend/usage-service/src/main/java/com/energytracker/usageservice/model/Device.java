package com.energytracker.usageservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Device {
        Long deviceId;
        String name;
        String type;
        String location;
        Double energyConsumed;
        Long userId;

}
