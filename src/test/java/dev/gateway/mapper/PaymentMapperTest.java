package dev.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gateway.model.DeclineCode;
import dev.gateway.model.Payment;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.model.Scenario;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

class PaymentMapperTest {
    private Payment payment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .merchantId("M001")
                .orderId("ORD-1001")
                .amount(new BigDecimal("100.50"))
                .currency("EUR")
                .paymentMethod("CARD")
                .status(PaymentStatus.RETRY_SCHEDULED)
                .idempotencyKey("mapping-key")
                .requestHash("request-fingerprint")
                .providerTransactionId("P-operation-1")
                .scenario(Scenario.INSUFFICIENT_FUNDS)
                .declineCode(DeclineCode.INSUFFICIENT_FUNDS)
                .attemptCount(2)
                .nextAttemptAt(Instant.parse("2026-09-19T12:00:03Z"))
                .leaseUntil(Instant.parse("2026-09-19T12:00:15Z"))
                .leaseToken(UUID.randomUUID())
                .createdAt(Instant.parse("2026-09-19T11:59:00Z"))
                .updatedAt(Instant.parse("2026-09-19T12:00:00Z"))
                .build();
    }

    @Test
    void entityRoundTripPreservesAllStateWithoutSharingMutableModels() {
        var original = payment();
        var entity = PaymentEntityMapper.toEntity(original);
        var restored = PaymentEntityMapper.toModel(entity);
        assertThat(restored).isEqualTo(original).isNotSameAs(original);
        entity.setStatus(PaymentStatus.FAILED);
        assertThat(original.getStatus()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
        assertThat(restored.getStatus()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
    }

    @Test
    void entityRoundTripPreservesClearedRecoveryFields() {
        var original =
                payment().toBuilder()
                        .status(PaymentStatus.SUCCESS)
                        .declineCode(null)
                        .nextAttemptAt(null)
                        .leaseUntil(null)
                        .leaseToken(null)
                        .build();
        assertThat(PaymentEntityMapper.toModel(PaymentEntityMapper.toEntity(original)))
                .isEqualTo(original);
    }

    @Test
    void apiViewPreservesPublicFieldsAndDoesNotExposeInternalState() throws Exception {
        var original = payment();
        var view = PaymentApiMapper.toView(original);
        assertThat(view.transactionId()).isEqualTo(original.getId());
        assertThat(view)
                .usingRecursiveComparison()
                .ignoringFields("transactionId")
                .isEqualTo(original);
        var json = new ObjectMapper().findAndRegisterModules().valueToTree(view);
        assertThat(json.has("requestHash")).isFalse();
        assertThat(json.has("leaseToken")).isFalse();
        assertThat(json.has("leaseUntil")).isFalse();
    }
}
