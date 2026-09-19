package dev.gateway.dto;

import dev.gateway.model.PaymentStatus;

import java.time.Instant;

public record EventView(long id, PaymentStatus status, String message, Instant createdAt) {

}
