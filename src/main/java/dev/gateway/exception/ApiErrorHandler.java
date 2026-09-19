package dev.gateway.exception;

import dev.gateway.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
@Log4j2
public class ApiErrorHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException exception, HttpServletRequest request) {
        return error(
                exception.getStatus(),
                exception.getCode(),
                exception.getMessage(),
                requestId(request),
                Map.of());
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ResponseEntity<Object> database(RuntimeException exception, HttpServletRequest request) {
        log.error("Database error, request {}", requestId(request), exception);
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "Storage is temporarily unavailable. Retry with the same idempotency key.",
                requestId(request),
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected error, request {}", requestId(request), exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed. Retry with the same idempotency key.",
                requestId(request),
                Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException invalid)
            invalid.getBindingResult()
                    .getFieldErrors()
                    .forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        String message =
                status.value() == 400
                        ? "Invalid request. Check the body, headers and field values."
                        : "The requested operation is not available.";
        return new ResponseEntity<>(
                new ErrorResponse(
                        status.value() == 400 ? "VALIDATION_ERROR" : "REQUEST_ERROR",
                        message,
                        Objects.toString(request.getAttribute("requestId", 0), "unknown"),
                        Instant.now(),
                        fields),
                headers,
                status);
    }

    private ResponseEntity<Object> error(
            HttpStatus status,
            String code,
            String message,
            String requestId,
            Map<String, String> fields) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(code, message, requestId, Instant.now(), fields));
    }

    private String requestId(HttpServletRequest request) {
        return Objects.toString(request.getAttribute("requestId"), "unknown");
    }
}
