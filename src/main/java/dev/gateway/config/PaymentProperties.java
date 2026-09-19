package dev.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("payment")
public record PaymentProperties(
        int maxAttempts,
        Duration retryDelay,
        Duration recoveryDelay,
        Duration leaseDuration,
        Duration providerTimeout) {

    public PaymentProperties {
        if (maxAttempts < 1 || maxAttempts > 3)
            throw new IllegalArgumentException("max-attempts must be 1–3");
        if (retryDelay.isNegative()
                || retryDelay.isZero()
                || recoveryDelay.isNegative()
                || recoveryDelay.isZero()
                || providerTimeout.isNegative()
                || providerTimeout.isZero()
                || leaseDuration.compareTo(providerTimeout.multipliedBy(2)) <= 0)
            throw new IllegalArgumentException(
                    "Delays must be positive; lease must exceed twice the provider timeout");
    }
}
