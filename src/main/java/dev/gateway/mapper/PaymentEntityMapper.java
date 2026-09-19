package dev.gateway.mapper;

import dev.gateway.model.Payment;
import dev.gateway.repository.entity.PaymentEntity;

import lombok.experimental.UtilityClass;

@UtilityClass
public class PaymentEntityMapper {

    public Payment toModel(PaymentEntity entity) {
        return Payment.builder()
                .id(entity.getId())
                .merchantId(entity.getMerchantId())
                .orderId(entity.getOrderId())
                .amount(entity.getAmount())
                .currency(entity.getCurrency())
                .paymentMethod(entity.getPaymentMethod())
                .status(entity.getStatus())
                .idempotencyKey(entity.getIdempotencyKey())
                .requestHash(entity.getRequestHash())
                .providerTransactionId(entity.getProviderTransactionId())
                .scenario(entity.getScenario())
                .declineCode(entity.getDeclineCode())
                .attemptCount(entity.getAttemptCount())
                .nextAttemptAt(entity.getNextAttemptAt())
                .leaseUntil(entity.getLeaseUntil())
                .leaseToken(entity.getLeaseToken())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PaymentEntity toEntity(Payment model) {
        PaymentEntity entity = new PaymentEntity();
        entity.setId(model.getId());
        entity.setMerchantId(model.getMerchantId());
        entity.setOrderId(model.getOrderId());
        entity.setAmount(model.getAmount());
        entity.setCurrency(model.getCurrency());
        entity.setPaymentMethod(model.getPaymentMethod());
        entity.setStatus(model.getStatus());
        entity.setIdempotencyKey(model.getIdempotencyKey());
        entity.setRequestHash(model.getRequestHash());
        entity.setProviderTransactionId(model.getProviderTransactionId());
        entity.setScenario(model.getScenario());
        entity.setDeclineCode(model.getDeclineCode());
        entity.setAttemptCount(model.getAttemptCount());
        entity.setNextAttemptAt(model.getNextAttemptAt());
        entity.setLeaseUntil(model.getLeaseUntil());
        entity.setLeaseToken(model.getLeaseToken());
        entity.setCreatedAt(model.getCreatedAt());
        entity.setUpdatedAt(model.getUpdatedAt());
        return entity;
    }
}
