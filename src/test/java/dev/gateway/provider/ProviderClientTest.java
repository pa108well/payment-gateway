package dev.gateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.gateway.config.PaymentProperties;
import dev.gateway.model.DeclineCode;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.model.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderClientTest {

    HttpServer server;

    java.util.concurrent.ExecutorService executor;

    ProviderClient client;

    final ProviderRequest claim = new ProviderRequest("P-operation-1", Scenario.SUCCESS, 1);

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        var context = mock(WebServerApplicationContext.class);
        var webServer = mock(WebServer.class);
        when(context.getWebServer()).thenReturn(webServer);
        when(webServer.getPort()).thenReturn(server.getAddress().getPort());
        var config =
                new PaymentProperties(
                        3,
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(15),
                        Duration.ofMillis(150));
        client = new ProviderClient(java.net.http.HttpClient.newBuilder().connectTimeout(config.providerTimeout()).build(), new ObjectMapper(), context, config);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    void respond(int status, String body) {
        server.createContext(
                "/mock-provider/payments",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 429, 500, 503})
    void nonSuccessHttpResponseIsUncertainNotADecline(int status) {
        respond(status, "{\"message\":\"unavailable\"}");
        assertThat(client.charge(claim).status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "not-json",
                    "{}",
                    "null",
                    "{\"providerTransactionId\":\"wrong\",\"status\":\"SUCCESS\"}",
                    "{\"providerTransactionId\":\"P-operation-1\",\"status\":\"RETRY_SCHEDULED\"}"
            })
    void malformedAndMismatchedResponsesAreUncertain(String body) {
        respond(200, body);
        assertThat(client.charge(claim).status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void timeoutBeforeAnyReplyIsUncertain() {
        server.createContext(
                "/mock-provider/payments",
                exchange -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        assertThat(client.charge(claim).status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void connectionFailureIsUncertain() {
        server.start();
        server.stop(0);
        assertThat(client.charge(claim).status()).isEqualTo(PaymentStatus.UNKNOWN);
    }

    @Test
    void validDeclinePreservesCodeAndProviderIdentity() {
        respond(
                200,
                "{\"providerTransactionId\":\"P-operation-1\",\"status\":\"FAILED\",\"declineCode\":\"EXPIRED_CARD\"}");
        var result = client.charge(claim);
        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.declineCode()).isEqualTo(DeclineCode.EXPIRED_CARD);
        assertThat(result.providerTransactionId()).isEqualTo(claim.operationKey());
    }
}
