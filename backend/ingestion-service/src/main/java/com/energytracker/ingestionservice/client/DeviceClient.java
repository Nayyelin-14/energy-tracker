package com.energytracker.ingestionservice.client;

import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.energytracker.ingestionservice.dto.DeviceDto;

@Component
public class DeviceClient {

        private final RestTemplate restTemplate;
        private final String baseUrl;

        public DeviceClient(
                        @Value("${device.service.url}") String baseUrl) {
                this.restTemplate = new RestTemplate();
                this.baseUrl = baseUrl;
        }

        public List<Long> getAllDeviceIds() {
                ResponseEntity<List<DeviceDto>> response = restTemplate.exchange(
                                baseUrl,
                                HttpMethod.GET,
                                null,
                                new ParameterizedTypeReference<List<DeviceDto>>() {
                                });

                List<DeviceDto> body = response.getBody();
                if (body == null) {
                        return List.of();
                }
                return body.stream()
                                .map(DeviceDto::deviceId)
                                .filter(Objects::nonNull)
                                .toList();
        }
}
