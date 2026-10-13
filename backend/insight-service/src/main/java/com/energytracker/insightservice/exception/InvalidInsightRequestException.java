package com.energytracker.insightservice.exception;

public class InvalidInsightRequestException extends RuntimeException {

        public InvalidInsightRequestException(String message) {
                super(message);
        }
}
