package com.energytracker.insightservice.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.energytracker.insightservice.config.InsightProviderRegistry;
import com.energytracker.insightservice.dto.InsightDto;
import com.energytracker.insightservice.dto.InsightType;
import com.energytracker.insightservice.dto.ProviderCatalogDto;
import com.energytracker.insightservice.service.InsightService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/api/v1/insight")
@AllArgsConstructor
public class InsightController {

        private final InsightService insightService;
        private final InsightProviderRegistry providerRegistry;
        private final InsightSseStreamer sseStreamer;

        @GetMapping("/providers")
        public ResponseEntity<ProviderCatalogDto> getProviders() {
                return ResponseEntity.ok(providerRegistry.catalog());
        }

        @GetMapping("/saving-tips/{userId}")
        public ResponseEntity<InsightDto> getSavingTips(
                        @PathVariable Long userId,
                        @RequestParam(required = false) String provider,
                        @RequestParam(required = false) String model,
                        @RequestParam(required = false) Integer days) {
                InsightDto insight = insightService.getSavingTips(userId, provider, model, days);
                return ResponseEntity.ok(insight);
        }

        @GetMapping(value = "/saving-tips/stream/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter streamSavingTips(
                        @PathVariable Long userId,
                        @RequestParam(required = false) String provider,
                        @RequestParam(required = false) String model,
                        @RequestParam(required = false) Integer days) {
                return sseStreamer.stream(userId, InsightType.SAVING_TIPS, provider, model, days);
        }

        @GetMapping("/overview/{userId}")
        public ResponseEntity<InsightDto> getOverView(
                        @PathVariable Long userId,
                        @RequestParam(required = false) String provider,
                        @RequestParam(required = false) String model,
                        @RequestParam(required = false) Integer days) {
                InsightDto insight = insightService.getOverView(userId, provider, model, days);
                return ResponseEntity.ok(insight);
        }

}
