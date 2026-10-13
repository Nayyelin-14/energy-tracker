package com.energytracker.apigateway.fallback;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CircuitBreakerFallbackController {

        private final CircuitBreakerRegistry circuitBreakerRegistry;

        public CircuitBreakerFallbackController(CircuitBreakerRegistry circuitBreakerRegistry) {
                this.circuitBreakerRegistry = circuitBreakerRegistry;
        }

        @RequestMapping("/fallback/{serviceName}")
        public ResponseEntity<Map<String, Object>> fallback(@PathVariable String serviceName) {
                CircuitBreaker.State state = getCircuitBreakerState(serviceName);

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
                body.put("error", "Service Unavailable");
                body.put("service", serviceName);
                body.put("circuitBreakerState", state != null ? state.name() : "UNKNOWN");
                body.put("message", getUserMessage(serviceName, state));
                body.put("timestamp", Instant.now().toString());

                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        private CircuitBreaker.State getCircuitBreakerState(String serviceName) {
                CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName);
                return circuitBreaker.getState();
        }

        private String getUserMessage(String serviceName, CircuitBreaker.State state) {
                if (state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN) {
                        return serviceName + " is currently down. The server is not responding. Please try again in a few moments.";
                }
                if (state == CircuitBreaker.State.HALF_OPEN) {
                        return serviceName + " is recovering. Please try again shortly.";
                }
                return serviceName + " is temporarily unavailable. Please try again later.";
        }
}
