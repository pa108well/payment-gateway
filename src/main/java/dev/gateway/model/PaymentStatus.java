package dev.gateway.model;

public enum PaymentStatus {
    PROCESSING,
    PENDING,
    UNKNOWN,
    RETRY_SCHEDULED,
    SUCCESS,
    FAILED;

    public boolean terminal() {
        return this == SUCCESS || this == FAILED;
    }
}
