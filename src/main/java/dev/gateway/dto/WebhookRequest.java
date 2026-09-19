package dev.gateway.dto;

import dev.gateway.model.PaymentStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WebhookRequest(
        @NotBlank @Size(max = 64) String providerTransactionId, @NotNull PaymentStatus status) {

}
