package dev.gateway.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

@RequiredArgsConstructor
@Getter
@Accessors(fluent = true)
public enum DeclineCode {
    INSUFFICIENT_FUNDS(true),
    ISSUER_UNAVAILABLE(true),
    PROCESSING_ERROR(true),
    EXPIRED_CARD(false),
    INVALID_CARD(false),
    LOST_CARD(false),
    STOLEN_CARD(false),
    DO_NOT_HONOR(false);

    private final boolean retryable;


}
