package com.energytracker.alertservice.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.energytracker.alertservice.dto.AlertDto;
import com.energytracker.alertservice.service.AlertQueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/alert")
@RequiredArgsConstructor
public class AlertController {

        private final AlertQueryService alertQueryService;

        @GetMapping("/user/{userId}")
        public ResponseEntity<List<AlertDto>> getAlertsForUser(
                        @PathVariable Long userId,
                        @RequestParam(required = false) Integer limit) {
                final List<AlertDto> alerts = alertQueryService.getAlertsForUser(userId, limit);
                return ResponseEntity.ok(alerts);
        }
}
