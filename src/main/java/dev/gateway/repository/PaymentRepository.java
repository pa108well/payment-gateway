package dev.gateway.repository;

import dev.gateway.mapper.PaymentEntityMapper;
import dev.gateway.model.DepositCommand;
import dev.gateway.model.Payment;
import dev.gateway.model.PaymentEvent;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.model.Scenario;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentRepository {

    PaymentJpaRepository jpa;

    JdbcTemplate jdbc;

    public Optional<Payment> findById(UUID id) {
        return jpa.findById(id).map(PaymentEntityMapper::toModel);
    }

    public Optional<Payment> lockById(UUID id) {
        return jpa.lockById(id).map(PaymentEntityMapper::toModel);
    }

    public Optional<Payment> findByMerchantIdAndIdempotencyKey(String merchantId, String key) {
        return jpa.findByMerchantIdAndIdempotencyKey(merchantId, key)
                .map(PaymentEntityMapper::toModel);
    }

    public List<Payment> findAllByOrderByCreatedAtDesc(Pageable page) {
        return jpa.findAllByOrderByCreatedAtDesc(page).stream()
                .map(PaymentEntityMapper::toModel)
                .toList();
    }

    public List<UUID> findDue(Instant now, Pageable page) {
        return jpa.findDue(now, page);
    }

    public void save(Payment payment) {
        jpa.save(PaymentEntityMapper.toEntity(payment));
    }

    public boolean insert(
            UUID id,
            DepositCommand request,
            String key,
            Scenario scenario,
            String hash,
            Instant now) {
        return jdbc.update(
                        """
INSERT INTO payments(transaction_id, merchant_id, order_id, amount, currency, payment_method,
  status, idempotency_key, request_hash, scenario, next_attempt_at, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?, ?)
ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
""",
                        id,
                        request.merchantId(),
                        request.orderId(),
                        request.amount(),
                        request.currency(),
                        request.paymentMethod(),
                        key,
                        hash,
                        scenario.name(),
                        Timestamp.from(now),
                        Timestamp.from(now),
                        Timestamp.from(now))
                == 1;
    }

    public void insertAttempt(Payment payment) {
        jdbc.update(
                "INSERT INTO payment_attempts(provider_transaction_id, transaction_id,"
                    + " attempt_number, status) VALUES (?, ?, ?, 'PROCESSING')",
                payment.getProviderTransactionId(),
                payment.getId(),
                payment.getAttemptCount());
    }

    public void updateAttempt(String providerId, PaymentStatus status) {
        jdbc.update(
                "UPDATE payment_attempts SET status = ? WHERE provider_transaction_id = ?",
                status.name(),
                providerId);
    }

    public Optional<UUID> findPaymentIdByProviderId(String providerId) {
        return jdbc
                .query(
                        "SELECT transaction_id FROM payment_attempts WHERE provider_transaction_id"
                            + " = ?",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        providerId)
                .stream()
                .findFirst();
    }

    public PaymentStatus findAttemptStatus(String providerId) {
        return PaymentStatus.valueOf(
                jdbc.queryForObject(
                        "SELECT status FROM payment_attempts WHERE provider_transaction_id = ?",
                        String.class,
                        providerId));
    }

    public List<PaymentEvent> findEvents(UUID id) {
        return jdbc.query(
                "SELECT * FROM payment_events WHERE transaction_id = ? ORDER BY id",
                (rs, row) ->
                        new PaymentEvent(
                                rs.getLong("id"),
                                PaymentStatus.valueOf(rs.getString("status")),
                                rs.getString("message"),
                                rs.getTimestamp("created_at").toInstant()),
                id);
    }

    public void addEvent(UUID id, PaymentStatus status, String message, Instant now) {
        jdbc.update(
                "INSERT INTO payment_events(transaction_id, status, message, created_at) VALUES (?,"
                    + " ?, ?, ?)",
                id,
                status.name(),
                message,
                Timestamp.from(now));
    }
}
