package dev.gateway.model;

import java.time.Instant;

public record PaymentEvent(long id, PaymentStatus status, String message, Instant createdAt) {

}
