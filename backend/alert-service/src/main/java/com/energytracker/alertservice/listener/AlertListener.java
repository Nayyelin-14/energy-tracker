package com.energytracker.alertservice.listener;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.energytracker.alertservice.service.AlertService;
import com.energytracker.kafka.event.AlertingEvent;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@AllArgsConstructor
public class AlertListener {

        private final AlertService alertService;

        @KafkaListener(topics = "energy-alerts", groupId = "alert-service")
        public void onAlertingEvent(AlertingEvent alertingEvent) {
                log.info("Received alert event: {}", alertingEvent);
                try {
                        alertService.processAlert(alertingEvent);
                } catch (Exception e) {
                        log.error("Failed to process alert event: {}", alertingEvent, e);
                }
        }
}
