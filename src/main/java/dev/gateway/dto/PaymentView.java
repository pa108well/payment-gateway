package dev.gateway.dto;

import dev.gateway.model.DeclineCode;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.model.Scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentView(
        UUID transactionId,
        String merchantId,
        String orderId,
        BigDecimal amount,
        String currency,
        String paymentMethod,
        PaymentStatus status,
        String idempotencyKey,
        String providerTransactionId,
        Scenario scenario,
        DeclineCode declineCode,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt) {

}
