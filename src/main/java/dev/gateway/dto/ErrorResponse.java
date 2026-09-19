package dev.gateway.dto;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        String requestId,
        Instant timestamp,
        Map<String, String> fields) {

}
