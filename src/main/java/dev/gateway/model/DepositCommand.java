package dev.gateway.model;

import java.math.BigDecimal;

public record DepositCommand(String merchantId, String orderId, BigDecimal amount, String currency,
                             String paymentMethod) {

}
