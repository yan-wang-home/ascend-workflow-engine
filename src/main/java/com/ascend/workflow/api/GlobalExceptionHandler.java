package com.ascend.workflow.api;

import com.ascend.workflow.domain.service.ResourceNotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.codec.DecodingException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid field-level constraint violations (blank, null, size, etc.)
    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleValidation(WebExchangeBindException ex,
                                                                       ServerWebExchange exchange) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return Mono.just(error(HttpStatus.BAD_REQUEST, message, exchange));
    }

    // Malformed JSON or unrecognized enum / type in request body; invalid path variable type
    @ExceptionHandler(ServerWebInputException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleServerWebInput(ServerWebInputException ex,
                                                                           ServerWebExchange exchange) {
        String message = decodeMessage(ex.getCause());
        if (message == null) {
            message = ex.getReason() != null ? ex.getReason() : "Invalid request input";
        }
        return Mono.just(error(HttpStatus.BAD_REQUEST, message, exchange));
    }

    // DecodingException not wrapped in ServerWebInputException (edge cases)
    @ExceptionHandler(DecodingException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleDecoding(DecodingException ex,
                                                                     ServerWebExchange exchange) {
        String message = decodeMessage(ex.getCause());
        return Mono.just(error(HttpStatus.BAD_REQUEST,
                message != null ? message : "Malformed request body", exchange));
    }

    // Other Spring HTTP-level exceptions: wrong Content-Type → 415, wrong method → 405, etc.
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleResponseStatus(ResponseStatusException ex,
                                                                           ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return Mono.just(error(status, message, exchange));
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

    @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
    public Mono<ResponseEntity<Map<String, Object>>> handleAccessDenied(Exception ex, ServerWebExchange exchange) {
        return Mono.just(error(HttpStatus.FORBIDDEN, "Access denied: insufficient role", exchange));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleDuplicateKey(DuplicateKeyException ex,
                                                                         ServerWebExchange exchange) {
        String msg = ex.getMessage();
        String detail = "Duplicate value — record already exists";
        if (msg != null && msg.contains("user_groups_name_key")) {
            detail = "A group with that name already exists";
        } else if (msg != null && msg.contains("users_email_key")) {
            detail = "A user with that email already exists";
        }
        return Mono.just(error(HttpStatus.CONFLICT, detail, exchange));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<Map<String, Object>>> handleGeneric(Exception ex, ServerWebExchange exchange) {
        log.error("Unhandled exception on {} {}", exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(), ex);
        return Mono.just(error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", exchange));
    }

    private String decodeMessage(Throwable cause) {
        if (cause instanceof DecodingException de) {
            return decodeMessage(de.getCause());
        }
        if (cause instanceof InvalidFormatException ife) {
            String field = ife.getPath().isEmpty() ? "value" : ife.getPath().get(0).getFieldName();
            String given = String.valueOf(ife.getValue());
            Class<?> target = ife.getTargetType();
            if (target != null && target.isEnum()) {
                String accepted = Arrays.stream(target.getEnumConstants())
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));
                return "Invalid value '" + given + "' for field '" + field
                        + "'. Accepted values: [" + accepted + "]";
            }
            return "Invalid value '" + given + "' for field '" + field + "'";
        }
        if (cause instanceof JsonProcessingException jpe) {
            return "Malformed JSON: " + jpe.getOriginalMessage();
        }
        return null;
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
