package com.energytracker.deviceservice.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

        private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

        @ExceptionHandler(ResourceNotFoundException.class)
        public ProblemDetail handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
                return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), request);
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
                return problem(HttpStatus.BAD_REQUEST, "Bad request",
                                ex.getMessage() == null ? "Invalid request" : ex.getMessage(), request);
        }

        @ExceptionHandler(NoResourceFoundException.class)
        public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
                String detail = "No resource found for " + request.getMethod() + " " + request.getRequestURI();
                return problem(HttpStatus.NOT_FOUND, "Resource not found", detail, request);
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        public ProblemDetail handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                        HttpServletRequest request) {
                ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
                                "Request method '" + ex.getMethod() + "' is not supported", request);
                Set<HttpMethod> supportedMethods = ex.getSupportedHttpMethods();
                if (supportedMethods != null && !supportedMethods.isEmpty()) {
                        problem.setProperty("supportedMethods",
                                        supportedMethods.stream().map(HttpMethod::name).toList());
                }
                return problem;
        }

        @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
        public ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                        HttpServletRequest request) {
                String detail = "Content-Type must be one of: "
                                + ex.getSupportedMediaTypes().stream().map(MediaType::toString).toList();
                return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", detail, request);
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
                Map<String, String> fieldErrors = new LinkedHashMap<>();
                for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
                        fieldErrors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
                }

                ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Validation failed",
                                "One or more fields are invalid", request);
                problem.setProperty("errors", fieldErrors);
                return problem;
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
                return problem(HttpStatus.BAD_REQUEST, "Malformed request",
                                "Request body is missing, malformed or contains an invalid value", request);
        }

        @ExceptionHandler(MethodArgumentTypeMismatchException.class)
        public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                        HttpServletRequest request) {
                String detail = "Parameter '" + ex.getName() + "' has an invalid value";
                return problem(HttpStatus.BAD_REQUEST, "Invalid parameter", detail, request);
        }

        @ExceptionHandler(DataIntegrityViolationException.class)
        public ProblemDetail handleConflict(DataIntegrityViolationException ex, HttpServletRequest request) {
                return problem(HttpStatus.CONFLICT, "Conflict",
                                "The request conflicts with existing data", request);
        }

        @ExceptionHandler(Exception.class)
        public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
                log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
                return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
                                "An unexpected error occurred", request);
        }

        private ProblemDetail problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
                ProblemDetail problem = ProblemDetail.forStatus(status);
                problem.setTitle(title);
                problem.setDetail(detail);
                problem.setProperty("timestamp", Instant.now());
                problem.setProperty("path", request.getRequestURI());
                return problem;
        }
}
