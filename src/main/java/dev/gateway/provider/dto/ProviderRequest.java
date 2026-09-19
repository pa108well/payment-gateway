package dev.gateway.provider.dto;

import dev.gateway.provider.model.Scenario;

public record ProviderRequest(String operationKey, Scenario scenario, int attempt) {

}
