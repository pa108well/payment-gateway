package dev.gateway.model;

public record DepositResult(Payment payment, boolean replayed) {}
