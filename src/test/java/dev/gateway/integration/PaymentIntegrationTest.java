package dev.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import dev.gateway.config.PaymentProperties;
import dev.gateway.dto.DepositRequest;
import dev.gateway.dto.ErrorResponse;
import dev.gateway.dto.PaymentDetail;
import dev.gateway.dto.PaymentView;
import dev.gateway.dto.WebhookRequest;
import dev.gateway.mapper.PaymentApiMapper;
import dev.gateway.model.DeclineCode;
import dev.gateway.model.PaymentStatus;
import dev.gateway.model.WebhookCommand;
import dev.gateway.provider.ProviderClient;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.dto.ProviderResult;
import dev.gateway.provider.model.Scenario;
import dev.gateway.scheduler.RecoveryWorker;
import dev.gateway.service.PaymentService;
import dev.gateway.service.PaymentStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"payment.worker-enabled=false", "payment.provider-timeout=400ms"})
@Testcontainers
class PaymentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TestRestTemplate http;

    @Autowired JdbcTemplate jdbc;

    @Autowired PaymentStore store;

    @Autowired PaymentService service;

    @Autowired ProviderClient provider;

    @Autowired PaymentProperties properties;

    PaymentView paymentView(UUID id) {
        return PaymentApiMapper.toView(store.get(id));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DROP TRIGGER IF EXISTS simulate_failure ON payments");
        jdbc.execute(
                "TRUNCATE payment_events, payment_attempts, provider_operations, payments RESTART"
                        + " IDENTITY CASCADE");
    }

    DepositRequest request() {
        return new DepositRequest("M001", "ORD-1001", new BigDecimal("100.50"), "EUR", "CARD");
    }

    ResponseEntity<PaymentView> deposit(String key, Scenario scenario) {
        return postDeposit(request(), key, scenario, PaymentView.class);
    }

    <T> ResponseEntity<T> postDeposit(
            Object request, String key, Scenario scenario, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) headers.set("Idempotency-Key", key);
        headers.set("X-Provider-Scenario", scenario.name());
        return http.postForEntity(
                "/api/v1/payments/deposit", new HttpEntity<>(request, headers), type);
    }

    <T> ResponseEntity<T> webhook(String providerId, PaymentStatus status, Class<T> type) {
        return http.postForEntity(
                "/api/v1/payments/webhook", new WebhookRequest(providerId, status), type);
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    void makeDue(UUID id) {
        jdbc.update(
                "UPDATE payments SET next_attempt_at=now()-interval '1 second',"
                        + " lease_until=now()-interval '1 second' WHERE transaction_id=?",
                id);
    }

    PaymentView retry(UUID id) {
        makeDue(id);
        service.process(id);
        return paymentView(id);
    }

    @Test
    void successfulPaymentIsPersistedWithProviderAndAudit() {
        var response = deposit("success", Scenario.SUCCESS);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var payment = response.getBody();
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment.providerTransactionId()).isNotBlank();
        assertThat(payment.amount()).isEqualByComparingTo("100.50");
        assertThat(payment.attemptCount()).isEqualTo(1);
        assertThat(response.getHeaders().getLocation().toString())
                .endsWith(payment.transactionId().toString());
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotBlank();
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(store.detail(payment.transactionId()).events()).hasSize(3);
    }

    @Test
    void duplicateReturnsSamePaymentAndCreatesNoNewEventsOrProviderOperations() {
        var first = deposit("same", Scenario.SUCCESS).getBody();
        long events = count("payment_events");
        var second = deposit("same", Scenario.SUCCESS);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getBody().transactionId()).isEqualTo(first.transactionId());
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(count("payment_events")).isEqualTo(events);
    }

    @Test
    void amountScaleIsNormalizedForIdempotency() {
        deposit("scale", Scenario.SUCCESS);
        var r = new DepositRequest("M001", "ORD-1001", new BigDecimal("100.5"), "EUR", "CARD");
        assertThat(postDeposit(r, "scale", Scenario.SUCCESS, PaymentView.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void sameKeyDifferentPayloadConflicts() {
        deposit("conflict", Scenario.SUCCESS);
        var changed =
                new DepositRequest("M001", "ORD-1001", new BigDecimal("101.50"), "EUR", "CARD");
        var response = postDeposit(changed, "conflict", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentScenarioConflicts() {
        deposit("scenario", Scenario.SUCCESS);
        assertThat(
                        postDeposit(request(), "scenario", Scenario.FAILED, ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void merchantScopesKeysAndOrderAllowsSeveralAttempts() {
        deposit("key-one", Scenario.SUCCESS);
        deposit("key-two", Scenario.SUCCESS);
        var other = new DepositRequest("M002", "ORD-1001", new BigDecimal("100.50"), "EUR", "CARD");
        assertThat(
                        postDeposit(other, "key-one", Scenario.SUCCESS, PaymentView.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(count("payments")).isEqualTo(3);
    }

    @Test
    void simultaneousRequestsReserveExactlyOnePaymentAndOneProviderCharge() throws Exception {
        int concurrency = 8;
        var barrier = new CyclicBarrier(concurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ResponseEntity<PaymentView>>> futures = new ArrayList<>();
            for (int i = 0; i < concurrency; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return deposit("race", Scenario.SUCCESS);
                                }));
            List<ResponseEntity<PaymentView>> responses = new ArrayList<>();
            for (var future : futures) responses.add(future.get(15, TimeUnit.SECONDS));
            assertThat(responses)
                    .allSatisfy(r -> assertThat(r.getStatusCode().value()).isIn(200, 201));
            assertThat(responses.stream().map(r -> r.getBody().transactionId()).distinct())
                    .hasSize(1);
            assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
        }
        assertThat(count("payments")).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "100000000000000000"})
    void invalidAmountsAreRejectedWithoutPersistence(String amount) {
        var invalid = new DepositRequest("M001", "ORD", new BigDecimal(amount), "EUR", "CARD");
        var response = postDeposit(invalid, "invalid", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fields()).containsKey("amount");
        assertThat(count("payments")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"XYZ", "eur", "EU", "JPY"})
    void invalidOrUnsupportedCurrencyIsRejected(String currency) {
        var invalid = new DepositRequest("M001", "ORD", BigDecimal.ONE, currency, "CARD");
        var response = postDeposit(invalid, "invalid", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().fields()).containsKey("currency");
    }

    @Test
    void missingHeaderMalformedJsonAndInvalidMethodUseConsistentErrors() {
        var noKey = postDeposit(request(), null, Scenario.SUCCESS, ErrorResponse.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var badJson = postDeposit("{ broken", "bad", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(badJson.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badJson.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(badJson.getBody().requestId()).isNotBlank();
        var wrongMethod = new DepositRequest("M001", "ORD", BigDecimal.ONE, "EUR", "CASH");
        assertThat(
                        postDeposit(wrongMethod, "bad", Scenario.SUCCESS, ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("payments")).isZero();
    }

    @Test
    void blankAndLongKeysAreRejected() {
        for (String key : List.of(" ", "x".repeat(129)))
            assertThat(
                            postDeposit(request(), key, Scenario.SUCCESS, ErrorResponse.class)
                                    .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @EnumSource(
            value = DeclineCode.class,
            names = {"EXPIRED_CARD", "INVALID_CARD", "LOST_CARD", "STOLEN_CARD", "DO_NOT_HONOR"})
    void hardDeclinesFailImmediately(DeclineCode code) {
        var p = deposit("hard", Scenario.valueOf(code.name())).getBody();
        assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.declineCode()).isEqualTo(code);
        assertThat(p.nextAttemptAt()).isNull();
        service.process(p.transactionId());
        assertThat(paymentView(p.transactionId()).attemptCount()).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
            value = DeclineCode.class,
            names = {"INSUFFICIENT_FUNDS", "ISSUER_UNAVAILABLE", "PROCESSING_ERROR"})
    void softDeclinesRetryWithBackoffAndStopAfterThreeAttempts(DeclineCode code) {
        var p = deposit("soft", Scenario.valueOf(code.name())).getBody();
        assertThat(p.status()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
        assertThat(Duration.between(p.updatedAt(), p.nextAttemptAt()).toMillis())
                .isBetween(2990L, 3050L);
        service.process(p.transactionId());
        assertThat(count("provider_operations")).isEqualTo(1);
        var second = retry(p.transactionId());
        assertThat(second.status()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
        assertThat(Duration.between(second.updatedAt(), second.nextAttemptAt()).toMillis())
                .isBetween(5990L, 6050L);
        var third = retry(p.transactionId());
        assertThat(third.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(third.attemptCount()).isEqualTo(3);
        assertThat(third.nextAttemptAt()).isNull();
        service.process(p.transactionId());
        assertThat(count("provider_operations")).isEqualTo(3);
    }

    @Test
    void softDeclinesCanRecoverSuccessfullyOnThirdAttempt() {
        var p = deposit("recover-soft", Scenario.SOFT_THEN_SUCCESS).getBody();
        retry(p.transactionId());
        var last = retry(p.transactionId());
        assertThat(last.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(last.declineCode()).isNull();
        assertThat(last.attemptCount()).isEqualTo(3);
    }

    @Test
    void providerFailureIsAValidPaymentResult() {
        var response = deposit("failed", Scenario.FAILED);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void realHttpTimeoutRecoversTheSameAcceptedOperation() {
        var p = deposit("timeout", Scenario.TIMEOUT).getBody();
        assertThat(p.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(p.attemptCount()).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
        var replay = deposit("timeout", Scenario.TIMEOUT).getBody();
        assertThat(replay.status()).isEqualTo(PaymentStatus.UNKNOWN);
        var recovered = retry(p.transactionId());
        assertThat(recovered.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recovered.providerTransactionId()).isEqualTo(p.providerTransactionId());
        assertThat(recovered.attemptCount()).isEqualTo(1);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test
    void duplicateAndConcurrentWebhooksAreNoOps() throws Exception {
        var p = deposit("pending", Scenario.PENDING).getBody();
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(4);
            List<Future<ResponseEntity<PaymentView>>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    barrier.await();
                                    return webhook(
                                            p.providerTransactionId(),
                                            PaymentStatus.SUCCESS,
                                            PaymentView.class);
                                }));
            for (var f : futures)
                assertThat(f.get(10, TimeUnit.SECONDS).getBody().status())
                        .isEqualTo(PaymentStatus.SUCCESS);
        }
        long events = count("payment_events");
        var before = paymentView(p.transactionId()).updatedAt();
        assertThat(
                        webhook(p.providerTransactionId(), PaymentStatus.SUCCESS, PaymentView.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(count("payment_events")).isEqualTo(events);
        assertThat(paymentView(p.transactionId()).updatedAt()).isEqualTo(before);
        assertThat(
                        store.detail(p.transactionId()).events().stream()
                                .filter(e -> e.message().startsWith("Webhook")))
                .hasSize(1);
    }

    @Test
    void duplicateWebhookForOldDeclinedAttemptDoesNotChangeCurrentAttempt() {
        var first = deposit("old-attempt", Scenario.SOFT_THEN_SUCCESS).getBody();
        var second = retry(first.transactionId());
        var response =
                webhook(first.providerTransactionId(), PaymentStatus.FAILED, PaymentView.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().providerTransactionId())
                .isEqualTo(second.providerTransactionId());
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.RETRY_SCHEDULED);
        assertThat(
                        webhook(
                                        first.providerTransactionId(),
                                        PaymentStatus.SUCCESS,
                                        ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(
            value = PaymentStatus.class,
            names = {"FAILED", "PENDING"})
    void successCannotBeChanged(PaymentStatus incoming) {
        var p = deposit("final", Scenario.SUCCESS).getBody();
        var response = webhook(p.providerTransactionId(), incoming, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().code()).isEqualTo("INVALID_STATUS_TRANSITION");
        assertThat(paymentView(p.transactionId()).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void failedCannotBecomeSuccessAndUnknownWebhookIs404() {
        var p = deposit("final-fail", Scenario.FAILED).getBody();
        assertThat(
                        webhook(
                                        p.providerTransactionId(),
                                        PaymentStatus.SUCCESS,
                                        ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(webhook("missing", PaymentStatus.SUCCESS, ErrorResponse.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        webhook(
                                        p.providerTransactionId(),
                                        PaymentStatus.PROCESSING,
                                        ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void earlyWebhookWinsOverLateProviderResponse() {
        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "early",
                        Scenario.PENDING,
                        "fingerprint");
        var claim = store.claim(registration.id()).orElseThrow();
        store.webhook(new WebhookCommand(claim.operationKey(), PaymentStatus.SUCCESS));
        store.apply(claim, new ProviderResult(claim.operationKey(), PaymentStatus.PENDING, null));
        assertThat(paymentView(registration.id()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentView(registration.id()).nextAttemptAt()).isNull();
    }

    @Test
    void earlyPendingWebhookStillAllowsFinalProviderResponse() {
        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "early-pending",
                        Scenario.SUCCESS,
                        "fingerprint");
        var claim = store.claim(registration.id()).orElseThrow();
        store.webhook(new WebhookCommand(claim.operationKey(), PaymentStatus.PENDING));
        store.apply(claim, new ProviderResult(claim.operationKey(), PaymentStatus.SUCCESS, null));
        assertThat(paymentView(registration.id()).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void earlyPendingWebhookIsNotDowngradedByHttpTimeout() {
        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "pending-timeout",
                        Scenario.PENDING,
                        "fingerprint");
        var claim = store.claim(registration.id()).orElseThrow();
        store.webhook(new WebhookCommand(claim.operationKey(), PaymentStatus.PENDING));
        store.apply(claim, ProviderResult.unknown(claim.operationKey()));
        var payment = paymentView(registration.id());
        assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.nextAttemptAt()).isNull();
    }

    @Test
    void expiredLeaseRecoversAfterCrashWithoutAnotherChargeAndFencesOldWorker() {
        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "crash",
                        Scenario.SUCCESS,
                        "fingerprint");
        var abandoned = store.claim(registration.id()).orElseThrow();
        provider.charge(
                new ProviderRequest(
                        abandoned.operationKey(), abandoned.scenario(), abandoned.attempt()));
        assertThat(store.claim(registration.id())).isEmpty();
        makeDue(registration.id());
        new RecoveryWorker(store, service).recover();
        var recovered = paymentView(registration.id());
        assertThat(recovered.status()).isEqualTo(PaymentStatus.SUCCESS);
        store.apply(abandoned, ProviderResult.unknown(abandoned.operationKey()));
        assertThat(paymentView(registration.id()).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("provider_operations")).isEqualTo(1);
        assertThat(recovered.attemptCount()).isEqualTo(1);
    }

    @Test
    void recoveryFindsSavedPaymentBeforeProviderWasEverCalled() {
        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "before-call",
                        Scenario.SUCCESS,
                        "fingerprint");
        new RecoveryWorker(store, service).recover();
        assertThat(paymentView(registration.id()).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void databaseFailureBeforeInsertReturns503WithoutCallingProvider() {
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION fail_payment_write() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RAISE EXCEPTION 'simulated database failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER simulate_failure BEFORE INSERT ON payments FOR EACH ROW EXECUTE"
                        + " FUNCTION fail_payment_write()");
        var response = postDeposit(request(), "db-error", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(response.getBody().message()).doesNotContain("simulated");
        assertThat(count("provider_operations")).isZero();
        assertThat(count("payments")).isZero();
    }

    @Test
    void databaseFailureAfterProviderAcceptanceRecoversWithoutDoubleCharging() {
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION fail_payment_success() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN IF NEW.status = 'SUCCESS' THEN RAISE EXCEPTION 'simulated"
                        + " commit failure'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER simulate_failure BEFORE UPDATE ON payments FOR EACH ROW EXECUTE"
                        + " FUNCTION fail_payment_success()");
        var response = postDeposit(request(), "db-after", Scenario.SUCCESS, ErrorResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(count("provider_operations")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER simulate_failure ON payments");
        UUID id = jdbc.queryForObject("SELECT transaction_id FROM payments", UUID.class);
        assertThat(retry(id).status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(count("provider_operations")).isEqualTo(1);
    }

    @Test
    void workerKeepsGoingAfterTemporaryDatabaseFailure() {

        var registration =
                store.register(
                        PaymentApiMapper.toCommand(request()),
                        "worker-db",
                        Scenario.SUCCESS,
                        "fingerprint");
        jdbc.execute(
                "CREATE OR REPLACE FUNCTION fail_payment_write() RETURNS trigger LANGUAGE plpgsql"
                        + " AS $$ BEGIN RAISE EXCEPTION 'simulated database failure'; END $$");
        jdbc.execute(
                "CREATE TRIGGER simulate_failure BEFORE UPDATE ON payments FOR EACH ROW EXECUTE"
                        + " FUNCTION fail_payment_write()");
        assertThatCode(() -> new RecoveryWorker(store, service).recover())
                .doesNotThrowAnyException();
        jdbc.execute("DROP TRIGGER simulate_failure ON payments");
        new RecoveryWorker(store, service).recover();
        assertThat(paymentView(registration.id()).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void readEndpointsAndUiAreAvailable() {
        var p = deposit("ui", Scenario.SUCCESS).getBody();
        assertThat(http.getForEntity("/api/v1/payments", PaymentView[].class).getBody()).hasSize(1);
        assertThat(
                        http.getForEntity(
                                        "/api/v1/payments/" + p.transactionId(),
                                        PaymentDetail.class)
                                .getBody()
                                .events())
                .hasSize(3);
        assertThat(http.getForEntity("/", String.class).getBody()).contains("Payment Studio");
        assertThat(
                        http.getForEntity(
                                        "/api/v1/payments/" + UUID.randomUUID(),
                                        ErrorResponse.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
