package com.energytracker.insightservice.exception;

import org.springframework.http.HttpStatus;

public class InsightProviderException extends RuntimeException {

        private final HttpStatus status;
        private final Long retryAfterSeconds;

        public InsightProviderException(HttpStatus status, String message) {
                this(status, message, null);
        }

        public InsightProviderException(HttpStatus status, String message, Long retryAfterSeconds) {
                super(message);
                this.status = status;
                this.retryAfterSeconds = retryAfterSeconds;
        }

        public HttpStatus status() {
                return status;
        }

        public Long retryAfterSeconds() {
                return retryAfterSeconds;
        }
}
