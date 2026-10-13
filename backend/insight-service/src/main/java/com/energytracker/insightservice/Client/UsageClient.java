package com.energytracker.insightservice.Client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.energytracker.insightservice.dto.UsageCoverageDto;
import com.energytracker.insightservice.dto.UsageDto;

@Component
public class UsageClient {
        private final RestTemplate restTemplate;

        private final String baseUrl;

        public UsageClient(
                        RestTemplate restTemplate,
                        @Value("${usage.service.url}") String baseUrl) {
                this.restTemplate = restTemplate;
                this.baseUrl = baseUrl;
        }

        public UsageDto getXDaysUsageForUser(Long userId, int days) {
                String url = UriComponentsBuilder.fromUriString(baseUrl)
                                .path("/{userId}")
                                .queryParam("days", days)
                                .buildAndExpand(userId).toUriString();

                ResponseEntity<UsageDto> response = restTemplate.getForEntity(url, UsageDto.class);

                return response.getBody();
        }

        public UsageCoverageDto getCoverage(Long userId) {
                String url = UriComponentsBuilder.fromUriString(baseUrl)
                                .path("/{userId}/coverage")
                                .buildAndExpand(userId).toUriString();

                ResponseEntity<UsageCoverageDto> response = restTemplate.getForEntity(url,
                                UsageCoverageDto.class);

                return response.getBody();
        }
}
