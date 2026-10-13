package com.energytracker.insightservice.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

import com.energytracker.insightservice.exception.InsightProviderException;
import com.energytracker.insightservice.exception.InvalidInsightRequestException;

@RestControllerAdvice
public class InsightExceptionHandler {

        @ExceptionHandler(InvalidInsightRequestException.class)
        public ResponseEntity<Map<String, String>> handleInvalidRequest(
                        InvalidInsightRequestException e) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(InsightProviderException.class)
        public ResponseEntity<Map<String, String>> handleProviderFailure(InsightProviderException e) {
                if (e.retryAfterSeconds() != null) {
                        return ResponseEntity.status(e.status())
                                        .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                                        .body(Map.of("error", e.getMessage()));
                }
                return ResponseEntity.status(e.status())
                                .body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler(RestClientException.class)
        public ResponseEntity<Map<String, String>> handleRestClient(RestClientException e) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(Map.of("error", "Usage service unavailable: " + e.getMessage()));
        }
}
