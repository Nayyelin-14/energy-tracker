package com.energytracker.alertservice.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.energytracker.alertservice.dto.AlertDto;
import com.energytracker.alertservice.entity.Alert;
import com.energytracker.alertservice.repository.AlertRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class AlertQueryService {

        private static final int MAX_LIMIT = 50;

        private final AlertRepository alertRepository;

        public List<AlertDto> getAlertsForUser(Long userId, Integer limit) {
                final int effectiveLimit = (limit == null || limit < 1 || limit > MAX_LIMIT) ? MAX_LIMIT : limit;

                log.info("Fetching up to {} alerts for user {}", effectiveLimit, userId);

                final List<Alert> alerts = alertRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);

                return alerts.stream()
                                .limit(effectiveLimit)
                                .map(this::toDto)
                                .toList();
        }

        private AlertDto toDto(Alert alert) {
                return AlertDto.builder()
                                .id(alert.getId())
                                .userId(alert.getUserId())
                                .message(alert.getMessage())
                                .threshold(alert.getThreshold())
                                .energyConsumed(alert.getEnergyConsumed())
                                .email(alert.getEmail())
                                .sent(alert.getSent())
                                .createdAt(alert.getCreatedAt())
                                .build();
        }
}
