package dev.gateway.model;

import java.util.List;

public record PaymentDetails(Payment payment, List<PaymentEvent> events) {

}
