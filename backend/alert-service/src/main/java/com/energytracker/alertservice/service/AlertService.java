package com.energytracker.alertservice.service;

import org.springframework.stereotype.Service;

import com.energytracker.alertservice.entity.Alert;
import com.energytracker.alertservice.repository.AlertRepository;
import com.energytracker.kafka.event.AlertingEvent;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@AllArgsConstructor
public class AlertService {

        private final AlertRepository alertRepository;
        private final EmailService emailService;

        public void processAlert(AlertingEvent alertingEvent) {

                if (alertingEvent == null) {
                        log.warn("Received empty alert event, skipping");
                        return;
                }

                if (alertingEvent.email() == null || alertingEvent.email().isBlank()) {
                        log.warn("Alert for user {} has no email address, skipping", alertingEvent.userId());
                        return;
                }

                final Alert alert = Alert.builder()
                                .userId(alertingEvent.userId())
                                .message(alertingEvent.message())
                                .threshold(alertingEvent.threshold())
                                .energyConsumed(alertingEvent.energyConsumed())
                                .email(alertingEvent.email())
                                .sent(false)
                                .build();

                final Alert savedAlert = alertRepository.save(alert);
                log.info("Persisted alert id {} for user {}", savedAlert.getId(), savedAlert.getUserId());

                final String subject = "Energy usage Alert for user " + alertingEvent.userId();
                final String body = "Alert: " + alertingEvent.message()
                                + "\nThreshold: " + alertingEvent.threshold()
                                + "\nEnergy Consumed: " + alertingEvent.energyConsumed();

                try {
                        emailService.sendEmail(alertingEvent.email(), subject, body);

                        savedAlert.setSent(true);
                        alertRepository.save(savedAlert);
                        log.info("Alert id {} sent to {}", savedAlert.getId(), savedAlert.getEmail());
                } catch (Exception e) {
                        log.error("Failed to send alert id {} to {}, alert stays unsent",
                                        savedAlert.getId(), savedAlert.getEmail(), e);
                }
        }
}
