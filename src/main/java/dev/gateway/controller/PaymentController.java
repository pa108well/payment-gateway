package dev.gateway.controller;

import dev.gateway.dto.DepositRequest;
import dev.gateway.dto.PaymentDetail;
import dev.gateway.dto.PaymentView;
import dev.gateway.dto.WebhookRequest;
import dev.gateway.mapper.PaymentApiMapper;
import dev.gateway.provider.model.Scenario;
import dev.gateway.service.PaymentService;
import dev.gateway.service.PaymentStore;

import jakarta.validation.Valid;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentController {

    PaymentService service;

    PaymentStore store;

    @PostMapping("/deposit")
    public ResponseEntity<PaymentView> deposit(
            @Valid @RequestBody DepositRequest request,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "X-Provider-Scenario", defaultValue = "SUCCESS")
                    Scenario scenario) {
        var result = service.deposit(PaymentApiMapper.toCommand(request), key, scenario);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/v1/payments/" + result.payment().getId()))
                .header("Idempotency-Replayed", String.valueOf(result.replayed()))
                .body(PaymentApiMapper.toView(result.payment()));
    }

    @PostMapping("/webhook")
    public PaymentView webhook(@Valid @RequestBody WebhookRequest request) {
        return PaymentApiMapper.toView(store.webhook(PaymentApiMapper.toCommand(request)));
    }

    @GetMapping
    public List<PaymentView> list() {
        return store.list().stream().map(PaymentApiMapper::toView).toList();
    }

    @GetMapping("/{id}")
    public PaymentDetail detail(@PathVariable UUID id) {
        return PaymentApiMapper.toDetail(store.detail(id));
    }
}
