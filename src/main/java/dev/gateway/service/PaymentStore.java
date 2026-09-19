package dev.gateway.service;

import static dev.gateway.model.PaymentStatus.FAILED;
import static dev.gateway.model.PaymentStatus.PENDING;
import static dev.gateway.model.PaymentStatus.PROCESSING;
import static dev.gateway.model.PaymentStatus.RETRY_SCHEDULED;
import static dev.gateway.model.PaymentStatus.SUCCESS;
import static dev.gateway.model.PaymentStatus.UNKNOWN;

import dev.gateway.config.PaymentProperties;
import dev.gateway.exception.ApiException;
import dev.gateway.model.Claim;
import dev.gateway.model.DepositCommand;
import dev.gateway.model.Payment;
import dev.gateway.model.PaymentDetails;
import dev.gateway.model.PaymentStatus;
import dev.gateway.model.Registration;
import dev.gateway.model.WebhookCommand;
import dev.gateway.provider.dto.ProviderResult;
import dev.gateway.provider.model.Scenario;
import dev.gateway.repository.PaymentRepository;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

import org.apache.logging.log4j.Level;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Log4j2
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class PaymentStore {

    PaymentRepository repository;

    Clock clock;

    PaymentProperties config;

    @Transactional
    public Registration register(
            DepositCommand request, String key, Scenario scenario, String hash) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        boolean inserted = repository.insert(id, request, key, scenario, hash, now);
        if (inserted) {
            event(id, PROCESSING, "Payment created. Idempotency key reserved.");
            return new Registration(id, true);
        }

        Payment existing =
                repository
                        .findByMerchantIdAndIdempotencyKey(request.merchantId(), key)
                        .orElseThrow();
        if (!existing.getRequestHash().equals(hash)) {
            log.warn(
                    "Idempotency conflict: request differs from the registered payment;"
                        + " transactionId={}",
                    existing.getId());
            throw ApiException.conflict(
                    "IDEMPOTENCY_CONFLICT",
                    "This key is already bound to a different request or scenario");
        }
        return new Registration(existing.getId(), false);
    }

    @Transactional
    public Optional<Claim> claim(UUID id) {
        Payment p = repository.lockById(id).orElseThrow(ApiException::notFound);
        Instant now = clock.instant();
        if (p.getStatus().terminal()
                || p.getNextAttemptAt() == null
                || p.getNextAttemptAt().isAfter(now)
                || (p.getLeaseUntil() != null && p.getLeaseUntil().isAfter(now)))
            return Optional.empty();

        boolean newAttempt = p.getAttemptCount() == 0 || p.getStatus() == RETRY_SCHEDULED;
        if (newAttempt) {
            p.setAttemptCount(p.getAttemptCount() + 1);
            p.setProviderTransactionId("P-" + p.getId() + "-" + p.getAttemptCount());
            p.setStatus(PROCESSING);
            repository.insertAttempt(p);
        }
        p.setLeaseToken(UUID.randomUUID());
        p.setLeaseUntil(now.plus(config.leaseDuration()));
        p.setUpdatedAt(now);
        repository.save(p);
        event(
                p.getId(),
                p.getStatus(),
                "Calling provider · attempt "
                        + p.getAttemptCount()
                        + " of "
                        + config.maxAttempts());
        logAfterCommit(
                Level.INFO,
                "Provider operation claimed: transactionId={}, operationKey={}, attempt={},"
                    + " recovering={}, leaseUntil={}",
                p.getId(),
                p.getProviderTransactionId(),
                p.getAttemptCount(),
                !newAttempt,
                p.getLeaseUntil());
        return Optional.of(
                new Claim(
                        p.getId(),
                        p.getLeaseToken(),
                        p.getProviderTransactionId(),
                        p.getScenario(),
                        p.getAttemptCount()));
    }

    @Transactional
    public void apply(Claim claim, ProviderResult result) {
        Payment p = repository.lockById(claim.id()).orElseThrow(ApiException::notFound);
        if (!Objects.equals(p.getLeaseToken(), claim.token()) || p.getStatus().terminal()) {
            log.info(
                    "Stale provider result ignored: transactionId={}, operationKey={}, attempt={},"
                        + " currentStatus={}, providerStatus={}",
                    p.getId(),
                    claim.operationKey(),
                    claim.attempt(),
                    p.getStatus(),
                    result.status());
            return;
        }
        PaymentStatus previousStatus = p.getStatus();
        p.setLeaseToken(null);
        p.setLeaseUntil(null);
        p.setUpdatedAt(clock.instant());
        p.setNextAttemptAt(null);

        if (p.getStatus() == PENDING && result.status() == UNKNOWN) {
            repository.save(p);
            logAfterCommit(
                    Level.INFO,
                    "Confirmed PENDING preserved despite uncertain provider reply:"
                        + " transactionId={}, operationKey={}",
                    p.getId(),
                    claim.operationKey());
            return;
        }
        p.setDeclineCode(result.declineCode());
        repository.updateAttempt(claim.operationKey(), result.status());
        String message;
        if (result.status() == UNKNOWN) {
            p.setStatus(UNKNOWN);
            p.setNextAttemptAt(clock.instant().plus(config.recoveryDelay()));
            message = "Provider outcome unknown. Recovering the same operation safely.";
        } else if (result.status() == FAILED
                && result.declineCode() != null
                && result.declineCode().retryable()
                && p.getAttemptCount() < config.maxAttempts()) {
            p.setStatus(RETRY_SCHEDULED);
            p.setNextAttemptAt(
                    clock.instant()
                            .plus(
                                    config.retryDelay()
                                            .multipliedBy(1L << (p.getAttemptCount() - 1))));
            message = result.declineCode() + " · soft decline; retry scheduled.";
        } else {
            p.setStatus(result.status());
            message =
                    result.declineCode() == null
                            ? "Provider returned " + p.getStatus()
                            : result.declineCode()
                                    + (result.declineCode().retryable()
                                            ? " · retry limit reached."
                                            : " · hard decline; no retry.");
        }
        repository.save(p);
        event(p.getId(), p.getStatus(), message);
        boolean retriesExhausted =
                p.getStatus() == FAILED
                        && result.declineCode() != null
                        && result.declineCode().retryable();
        logAfterCommit(
                p.getStatus() == UNKNOWN || retriesExhausted ? Level.WARN : Level.INFO,
                "Provider outcome committed: transactionId={}, operationKey={}, fromStatus={},"
                    + " toStatus={}, declineCode={}, attempt={}, maxAttempts={}, nextAttemptAt={},"
                    + " retriesExhausted={}",
                p.getId(),
                claim.operationKey(),
                previousStatus,
                p.getStatus(),
                p.getDeclineCode(),
                p.getAttemptCount(),
                config.maxAttempts(),
                p.getNextAttemptAt(),
                retriesExhausted);
    }

    @Transactional
    public Payment webhook(WebhookCommand request) {
        if (!Set.of(SUCCESS, FAILED, PENDING).contains(request.status())) {
            log.warn("Webhook rejected: unsupported status={}", request.status());
            throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_WEBHOOK_STATUS",
                    "Webhook status must be SUCCESS, FAILED or PENDING");
        }
        UUID id =
                repository
                        .findPaymentIdByProviderId(request.providerTransactionId())
                        .orElseThrow(
                                () -> {
                                    log.warn("Webhook rejected: provider operation not found");
                                    return ApiException.notFound();
                                });
        Payment p = repository.lockById(id).orElseThrow(ApiException::notFound);
        PaymentStatus attemptStatus = repository.findAttemptStatus(request.providerTransactionId());

        if (attemptStatus == request.status()) {
            log.debug(
                    "Duplicate webhook ignored: transactionId={}, attemptStatus={},"
                        + " currentStatus={}",
                    p.getId(),
                    attemptStatus,
                    p.getStatus());
            return p;
        }
        if (attemptStatus.terminal()
                || p.getStatus().terminal()
                || !request.providerTransactionId().equals(p.getProviderTransactionId())) {
            log.warn(
                    "Webhook transition rejected: transactionId={}, attemptStatus={},"
                        + " currentStatus={}, requestedStatus={}, historicalAttempt={}",
                    p.getId(),
                    attemptStatus,
                    p.getStatus(),
                    request.status(),
                    !request.providerTransactionId().equals(p.getProviderTransactionId()));
            throw ApiException.conflict(
                    "INVALID_STATUS_TRANSITION",
                    "Cannot change " + attemptStatus + " to " + request.status());
        }
        PaymentStatus previousStatus = p.getStatus();
        repository.updateAttempt(request.providerTransactionId(), request.status());
        p.setStatus(request.status());
        p.setUpdatedAt(clock.instant());
        p.setNextAttemptAt(null);
        p.setDeclineCode(null);

        if (p.getStatus().terminal()) {
            p.setLeaseToken(null);
            p.setLeaseUntil(null);
        }
        repository.save(p);
        event(p.getId(), p.getStatus(), "Webhook confirmed " + p.getStatus());
        logAfterCommit(
                Level.INFO,
                "Webhook outcome committed: transactionId={}, operationKey={}, fromStatus={},"
                    + " toStatus={}",
                p.getId(),
                p.getProviderTransactionId(),
                previousStatus,
                p.getStatus());
        return p;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return repository.findById(id).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public List<Payment> list() {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100));
    }

    @Transactional(readOnly = true)
    public PaymentDetails detail(UUID id) {
        return new PaymentDetails(get(id), repository.findEvents(id));
    }

    @Transactional(readOnly = true)
    public List<UUID> due() {
        return repository.findDue(clock.instant(), PageRequest.of(0, 20));
    }

    private void event(UUID id, PaymentStatus status, String message) {
        repository.addEvent(id, status, message, clock.instant());
    }

    private void logAfterCommit(Level level, String message, Object... arguments) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.log(level, message, arguments);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.log(level, message, arguments);
                    }
                });
    }
}
