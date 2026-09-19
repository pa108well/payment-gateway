package dev.gateway.model;

public record WebhookCommand(String providerTransactionId, PaymentStatus status) {

}
