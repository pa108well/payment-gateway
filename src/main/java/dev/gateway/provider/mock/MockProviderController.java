package dev.gateway.provider.mock;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

import dev.gateway.config.PaymentProperties;
import dev.gateway.exception.ApiException;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.dto.ProviderResult;
import dev.gateway.provider.model.Scenario;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock-provider")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MockProviderController {

    MockProviderStore store;

    PaymentProperties config;


    @PostMapping("/payments")
    public ProviderResult charge(@RequestBody ProviderRequest request) throws InterruptedException {
        if (request.operationKey() == null
                || !request.operationKey().matches("P-[0-9a-f-]{36}-[1-3]")
                || request.scenario() == null
                || request.attempt() < 1
                || request.attempt() > 3)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PROVIDER_REQUEST",
                    "Invalid provider operation");
        var outcome = store.charge(request);

        if (outcome.created() && request.scenario() == Scenario.TIMEOUT)
            Thread.sleep(config.providerTimeout().multipliedBy(3).toMillis());
        return outcome.result();
    }
}
