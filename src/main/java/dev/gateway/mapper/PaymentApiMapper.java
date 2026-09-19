package dev.gateway.mapper;

import dev.gateway.dto.DepositRequest;
import dev.gateway.dto.EventView;
import dev.gateway.dto.PaymentDetail;
import dev.gateway.dto.PaymentView;
import dev.gateway.dto.WebhookRequest;
import dev.gateway.model.DepositCommand;
import dev.gateway.model.Payment;
import dev.gateway.model.PaymentDetails;
import dev.gateway.model.PaymentEvent;
import dev.gateway.model.WebhookCommand;

import lombok.experimental.UtilityClass;

@UtilityClass
public class PaymentApiMapper {

    public DepositCommand toCommand(DepositRequest request) {
        return new DepositCommand(
                request.merchantId(),
                request.orderId(),
                request.amount(),
                request.currency(),
                request.paymentMethod());
    }

    public WebhookCommand toCommand(WebhookRequest request) {
        return new WebhookCommand(request.providerTransactionId(), request.status());
    }

    public PaymentView toView(Payment payment) {
        return new PaymentView(
                payment.getId(),
                payment.getMerchantId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getPaymentMethod(),
                payment.getStatus(),
                payment.getIdempotencyKey(),
                payment.getProviderTransactionId(),
                payment.getScenario(),
                payment.getDeclineCode(),
                payment.getAttemptCount(),
                payment.getNextAttemptAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    public EventView toView(PaymentEvent event) {
        return new EventView(event.id(), event.status(), event.message(), event.createdAt());
    }

    public PaymentDetail toDetail(PaymentDetails detail) {
        return new PaymentDetail(
                toView(detail.payment()),
                detail.events().stream().map(PaymentApiMapper::toView).toList());
    }
}
