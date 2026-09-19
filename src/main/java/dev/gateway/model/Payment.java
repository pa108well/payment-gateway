package dev.gateway.model;

import dev.gateway.provider.model.Scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import lombok.Data;
import lombok.Builder;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

@Data
@Builder(toBuilder = true)
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Payment {

    UUID id;

    String merchantId;

    String orderId;

    BigDecimal amount;

    String currency;

    String paymentMethod;

    PaymentStatus status;

    String idempotencyKey;

    String requestHash;

    String providerTransactionId;

    Scenario scenario;

    DeclineCode declineCode;

    int attemptCount;

    Instant nextAttemptAt;

    Instant leaseUntil;

    UUID leaseToken;

    Instant createdAt;

    Instant updatedAt;
}
