package dev.gateway.provider.model;

public enum Scenario {
    SUCCESS,
    PENDING,
    FAILED,
    TIMEOUT,
    SOFT_THEN_SUCCESS,
    INSUFFICIENT_FUNDS,
    ISSUER_UNAVAILABLE,
    PROCESSING_ERROR,
    EXPIRED_CARD,
    INVALID_CARD,
    LOST_CARD,
    STOLEN_CARD,
    DO_NOT_HONOR
}
