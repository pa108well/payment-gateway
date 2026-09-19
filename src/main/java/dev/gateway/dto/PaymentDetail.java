package dev.gateway.dto;

import java.util.List;

public record PaymentDetail(PaymentView payment, List<EventView> events) {

}
