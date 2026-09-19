package dev.gateway.repository.entity;

import dev.gateway.model.PaymentStatus;
import dev.gateway.model.DeclineCode;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

import dev.gateway.provider.model.Scenario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class PaymentEntity {

    @Id
    @Column(name = "transaction_id")
    UUID id;

    @Column(nullable = false, length = 64)
    String merchantId;

    @Column(nullable = false, length = 128)
    String orderId;

    @Column(nullable = false, precision = 19, scale = 2)
    BigDecimal amount;

    @Column(nullable = false, length = 3)
    String currency;

    @Column(nullable = false, length = 16)
    String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    PaymentStatus status;

    @Column(nullable = false, length = 128)
    String idempotencyKey;

    @Column(nullable = false, length = 64)
    String requestHash;

    @Column(length = 64)
    String providerTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    Scenario scenario;

    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    DeclineCode declineCode;

    int attemptCount;

    Instant nextAttemptAt;

    Instant leaseUntil;

    UUID leaseToken;

    @Column(nullable = false)
    Instant createdAt;

    @Column(nullable = false)
    Instant updatedAt;

}
