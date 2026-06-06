package com.ascend.workflow.api;

import com.ascend.workflow.domain.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidation(WebExchangeBindException ex,
                                                                       ServerWebExchange exchange) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Mono.just(error(HttpStatus.BAD_REQUEST, message, exchange));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleNotFound(ResourceNotFoundException ex,
                                                                     ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.NOT_FOUND, ex.getMessage(), exchange));
    }

    @ExceptionHandler(SecurityException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleForbidden(SecurityException ex,
                                                                      ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.FORBIDDEN, ex.getMessage(), exchange));
    }

    @ExceptionHandler(IllegalStateException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleConflict(IllegalStateException ex,
                                                                     ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.CONFLICT, ex.getMessage(), exchange));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleBadRequest(IllegalArgumentException ex,
                                                                       ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.BAD_REQUEST, ex.getMessage(), exchange));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGeneric(Exception ex, ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", exchange));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, ServerWebExchange exchange) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", exchange.getRequest().getPath().value(),
                "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
