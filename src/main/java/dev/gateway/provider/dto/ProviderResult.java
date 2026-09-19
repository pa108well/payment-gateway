package dev.gateway.provider.dto;

import dev.gateway.model.DeclineCode;
import dev.gateway.model.PaymentStatus;

public record ProviderResult(
        String providerTransactionId, PaymentStatus status, DeclineCode declineCode) {

    public static ProviderResult unknown(String id) {
        return new ProviderResult(id, PaymentStatus.UNKNOWN, null);
    }
}
