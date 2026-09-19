package dev.gateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gateway.exception.ApiException;
import dev.gateway.model.DepositCommand;
import dev.gateway.model.DepositResult;
import dev.gateway.provider.ProviderClient;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.model.Scenario;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Log4j2
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentService {
    PaymentStore store;
    ProviderClient provider;
    ObjectMapper mapper;

    public DepositResult deposit(DepositCommand request, String key, Scenario scenario) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.equals(key.strip())) {
            log.warn("Deposit rejected: invalid idempotency key");
            throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Supply a nonblank Idempotency-Key of at most 128 characters without"
                            + " surrounding spaces");
        }
        var registration = store.register(request, key, scenario, fingerprint(request, scenario));
        if (registration.created()) {
            log.info(
                    "Deposit registered; starting processing: transactionId={}", registration.id());
            process(registration.id());
        } else {
            log.debug("Idempotent deposit replay: transactionId={}", registration.id());
        }
        return new DepositResult(store.get(registration.id()), !registration.created());
    }

    public void process(UUID id) {
        var claimed = store.claim(id);
        if (claimed.isEmpty()) {
            log.debug(
                    "Payment processing skipped: terminal, not due or already leased;"
                        + " transactionId={}",
                    id);
            return;
        }
        var claim = claimed.get();
        var result =
                provider.charge(
                        new ProviderRequest(
                                claim.operationKey(), claim.scenario(), claim.attempt()));
        try {
            store.apply(claim, result);
        } catch (DataAccessException | TransactionException persistenceFailure) {
            log.error(
                    "Provider outcome could not be persisted; recovery must reuse the same"
                        + " operation: transactionId={}, operationKey={}, attempt={},"
                        + " providerStatus={}",
                    id,
                    claim.operationKey(),
                    claim.attempt(),
                    result.status());
            throw persistenceFailure;
        }
    }

    private String fingerprint(DepositCommand request, Scenario scenario) {
        try {
            String canonical =
                    mapper.writeValueAsString(
                            List.of(
                                    request.merchantId(),
                                    request.orderId(),
                                    request.amount().stripTrailingZeros().toPlainString(),
                                    request.currency(),
                                    request.paymentMethod(),
                                    scenario));
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            log.error("Failed to compute deposit request fingerprint", impossible);
            throw new IllegalStateException("Could not fingerprint request", impossible);
        }
    }
}
