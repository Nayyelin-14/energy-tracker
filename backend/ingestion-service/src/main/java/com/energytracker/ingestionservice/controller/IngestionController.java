package com.energytracker.ingestionservice.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.energytracker.ingestionservice.dto.EnergyUsageDto;
import com.energytracker.ingestionservice.service.IngestionService;

import lombok.AllArgsConstructor;

@RestController
@RequestMapping("/api/v1/ingestion")
@AllArgsConstructor
public class IngestionController {

        private final IngestionService ingestionService;

        @PostMapping
        @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
        public void ingestData(@RequestBody EnergyUsageDto usageDto) {
                ingestionService.ingestEnergyUsage(usageDto);
        }
}
