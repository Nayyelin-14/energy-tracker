package com.energytracker.usageservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.energytracker.usageservice.dto.UsageCoverageDto;
import com.energytracker.usageservice.dto.UsageDto;
import com.energytracker.usageservice.service.UsageService;

import lombok.AllArgsConstructor;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {
        private final UsageService usageService;

        @GetMapping("/{userId}")
        public ResponseEntity<UsageDto> getUserDeviceUsage(
                        @PathVariable Long userId,
                        @RequestParam(defaultValue = "3") int days) {

                final UsageDto usage = usageService.getXDaysUsageForUser(userId, days);
                return ResponseEntity.ok(usage);
        }

        @GetMapping("/{userId}/coverage")
        public ResponseEntity<UsageCoverageDto> getUserDataCoverage(@PathVariable Long userId) {
                final UsageCoverageDto coverage = usageService.getCoverage(userId);
                return ResponseEntity.ok(coverage);
        }

}
