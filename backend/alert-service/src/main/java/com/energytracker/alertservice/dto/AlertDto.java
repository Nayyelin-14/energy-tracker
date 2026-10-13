package com.energytracker.alertservice.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertDto {
        private Long id;
        private Long userId;
        private String message;
        private Double threshold;
        private Double energyConsumed;
        private String email;
        private Boolean sent;
        private LocalDateTime createdAt;
}
