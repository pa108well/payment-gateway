package dev.gateway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.gateway.config.PaymentProperties;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.dto.ProviderResult;

import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Set;

@Component
@Log4j2
@RequiredArgsConstructor
@FieldDefaults(level = lombok.AccessLevel.PRIVATE, makeFinal = true)
public class ProviderClient {

    HttpClient client;

    ObjectMapper mapper;

    WebServerApplicationContext context;

    PaymentProperties config;

    public ProviderResult charge(ProviderRequest chargeRequest) {
        log.debug(
                "Calling provider: operationKey={}, attempt={}",
                chargeRequest.operationKey(),
                chargeRequest.attempt());
        try {
            String body = mapper.writeValueAsString(chargeRequest);
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:"
                                                    + context.getWebServer().getPort()
                                                    + "/mock-provider/payments"))
                            .timeout(config.providerTimeout())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn(
                        "Provider returned HTTP {}; outcome remains unknown: operationKey={},"
                            + " attempt={}",
                        response.statusCode(),
                        chargeRequest.operationKey(),
                        chargeRequest.attempt());
                return ProviderResult.unknown(chargeRequest.operationKey());
            }
            ProviderResult result = mapper.readValue(response.body(), ProviderResult.class);
            if (result == null
                    || !chargeRequest.operationKey().equals(result.providerTransactionId())
                    || result.status() == null
                    || !Set.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, PaymentStatus.PENDING)
                            .contains(result.status())) {
                log.warn(
                        "Invalid provider response status or operation identity; outcome remains"
                            + " unknown: operationKey={}, attempt={}",
                        chargeRequest.operationKey(),
                        chargeRequest.attempt());
                return ProviderResult.unknown(chargeRequest.operationKey());
            }
            log.debug(
                    "Provider response received: operationKey={}, attempt={}, status={},"
                        + " declineCode={}",
                    chargeRequest.operationKey(),
                    chargeRequest.attempt(),
                    result.status(),
                    result.declineCode());
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn(
                    "Provider call interrupted; outcome remains unknown: operationKey={},"
                        + " attempt={}",
                    chargeRequest.operationKey(),
                    chargeRequest.attempt());
            return ProviderResult.unknown(chargeRequest.operationKey());
        } catch (HttpTimeoutException timeout) {
            log.warn(
                    "Provider timeout after {}; outcome remains unknown: operationKey={},"
                        + " attempt={}",
                    config.providerTimeout(),
                    chargeRequest.operationKey(),
                    chargeRequest.attempt());
            return ProviderResult.unknown(chargeRequest.operationKey());
        } catch (JsonProcessingException invalidPayload) {
            log.warn(
                    "Provider JSON processing failed; outcome remains unknown: operationKey={},"
                        + " attempt={}, errorType={}",
                    chargeRequest.operationKey(),
                    chargeRequest.attempt(),
                    invalidPayload.getClass().getSimpleName());
            return ProviderResult.unknown(chargeRequest.operationKey());
        } catch (IOException networkFailure) {
            log.warn(
                    "Provider I/O failure; outcome remains unknown: operationKey={}, attempt={},"
                        + " errorType={}",
                    chargeRequest.operationKey(),
                    chargeRequest.attempt(),
                    networkFailure.getClass().getSimpleName());
            return ProviderResult.unknown(chargeRequest.operationKey());
        }
    }
}
