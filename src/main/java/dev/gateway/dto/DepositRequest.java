package dev.gateway.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record DepositRequest(
        @NotBlank @Size(max = 64) String merchantId,
        @NotBlank @Size(max = 128) String orderId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull @Pattern(regexp = "EUR|USD|GBP", message = "Supported currencies: EUR, USD, GBP")
        String currency,
        @NotNull @Pattern(regexp = "CARD", message = "Supported payment method: CARD")
        String paymentMethod) {

}
