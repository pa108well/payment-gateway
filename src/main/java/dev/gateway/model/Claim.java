package dev.gateway.model;

import dev.gateway.provider.model.Scenario;

import java.util.UUID;

public record Claim(UUID id, UUID token, String operationKey, Scenario scenario, int attempt) {}
