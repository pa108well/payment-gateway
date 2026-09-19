package dev.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gateway.config.PaymentProperties;
import dev.gateway.model.Claim;
import dev.gateway.model.Payment;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.ProviderClient;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.dto.ProviderResult;
import dev.gateway.provider.model.Scenario;
import dev.gateway.repository.PaymentRepository;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class PaymentLoggingTest {
    final List<LogEvent> events = new ArrayList<>();
    final List<Logger> loggers = new ArrayList<>();
    final List<Level> levels = new ArrayList<>();
    final AbstractAppender appender =
            new AbstractAppender("payment-test", null, null, false, Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    events.add(event.toImmutable());
                }
            };
    final PaymentProperties properties =
            new PaymentProperties(
                    3,
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(15),
                    Duration.ofMillis(800));
    final PaymentRepository repository = mock(PaymentRepository.class);
    final PaymentStore store =
            new PaymentStore(
                    repository,
                    Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC),
                    properties);
    Payment payment;
    Claim claim;

    @BeforeEach
    void setup() {
        appender.start();
        for (Class<?> type :
                List.of(PaymentStore.class, PaymentService.class, ProviderClient.class)) {
            Logger logger = (Logger) LogManager.getLogger(type);
            loggers.add(logger);
            levels.add(logger.getLevel());
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        claim = new Claim(id, token, "P-" + id + "-1", Scenario.SUCCESS, 1);
        payment =
                Payment.builder()
                        .id(id)
                        .status(PaymentStatus.PROCESSING)
                        .leaseToken(token)
                        .providerTransactionId(claim.operationKey())
                        .attemptCount(1)
                        .build();
        when(repository.lockById(id)).thenReturn(Optional.of(payment));
    }

    @AfterEach
    void cleanup() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).removeAppender(appender);
            loggers.get(i).setLevel(levels.get(i));
        }
        appender.stop();
    }

    @Test
    void statusChangeIsLoggedOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        store.apply(claim, new ProviderResult(claim.operationKey(), PaymentStatus.SUCCESS, null));
        assertThat(events).isEmpty();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(events.getFirst().getMessage().getFormattedMessage())
                .contains(
                        "Provider outcome committed",
                        "fromStatus=PROCESSING",
                        "toStatus=SUCCESS",
                        claim.id().toString())
                .doesNotContain(claim.token().toString());
    }

    @Test
    void rolledBackTransactionDoesNotLogCommittedOutcome() {
        TransactionSynchronizationManager.initSynchronization();
        store.apply(claim, new ProviderResult(claim.operationKey(), PaymentStatus.SUCCESS, null));
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(
                        sync ->
                                sync.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(events).isEmpty();
    }

    @Test
    void staleProviderResultIsLoggedWithoutChangingPayment() {
        payment.setLeaseToken(UUID.randomUUID());
        store.apply(claim, new ProviderResult(claim.operationKey(), PaymentStatus.SUCCESS, null));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        verify(repository, never()).save(any());
        assertThat(events)
                .anySatisfy(
                        event ->
                                assertThat(event.getMessage().getFormattedMessage())
                                        .contains(
                                                "Stale provider result ignored",
                                                claim.id().toString()));
    }

    @Test
    void persistenceFailureAfterProviderResponseHasRecoveryContextAndIsPropagated() {
        PaymentStore mockedStore = mock(PaymentStore.class);
        ProviderClient provider = mock(ProviderClient.class);
        var result = new ProviderResult(claim.operationKey(), PaymentStatus.SUCCESS, null);
        var failure = new DataAccessResourceFailureException("storage unavailable");
        when(mockedStore.claim(claim.id())).thenReturn(Optional.of(claim));
        when(provider.charge(any())).thenReturn(result);
        doThrow(failure).when(mockedStore).apply(claim, result);
        var service = new PaymentService(mockedStore, provider, new ObjectMapper());
        assertThatThrownBy(() -> service.process(claim.id())).isSameAs(failure);
        assertThat(events)
                .anySatisfy(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                            assertThat(event.getMessage().getFormattedMessage())
                                    .contains(
                                            "recovery must reuse the same operation",
                                            claim.id().toString(),
                                            claim.operationKey(),
                                            "providerStatus=SUCCESS");
                        });
    }

    @Test
    void providerIoFailureIsLoggedWithoutLeakingExceptionPayload() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var context = mock(WebServerApplicationContext.class);
        var webServer = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(8080);
        when(client.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("sensitive-response-data"));
        var provider = new ProviderClient(client, new ObjectMapper(), context, properties);
        var result =
                provider.charge(new ProviderRequest(claim.operationKey(), Scenario.SUCCESS, 1));
        assertThat(result.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(events)
                .anySatisfy(
                        event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getMessage().getFormattedMessage())
                                    .contains(
                                            "Provider I/O failure",
                                            claim.operationKey(),
                                            "errorType=IOException");
                        });
        assertThat(events)
                .allSatisfy(
                        event -> {
                            assertThat(event.getMessage().getFormattedMessage())
                                    .doesNotContain("sensitive-response-data");
                            assertThat(event.getThrown()).isNull();
                        });
    }
}
