package dev.gateway.provider.mock;

import lombok.RequiredArgsConstructor;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;

import dev.gateway.model.DeclineCode;
import dev.gateway.model.PaymentStatus;
import dev.gateway.provider.dto.ProviderRequest;
import dev.gateway.provider.dto.ProviderResult;
import dev.gateway.provider.model.Scenario;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class MockProviderStore {

    JdbcTemplate jdbc;


    public record Outcome(ProviderResult result, boolean created) {

    }

    @Transactional
    public Outcome charge(ProviderRequest request) {
        DeclineCode decline =
                switch (request.scenario()) {
                    case SUCCESS, PENDING, TIMEOUT -> null;
                    case FAILED -> DeclineCode.DO_NOT_HONOR;
                    case SOFT_THEN_SUCCESS -> request.attempt() < 3 ? DeclineCode.ISSUER_UNAVAILABLE : null;
                    default -> DeclineCode.valueOf(request.scenario().name());
                };
        PaymentStatus status =
                decline != null
                        ? PaymentStatus.FAILED
                        : request.scenario() == Scenario.PENDING
                        ? PaymentStatus.PENDING
                        : PaymentStatus.SUCCESS;
        int inserted =
                jdbc.update(
                        """
                                INSERT INTO provider_operations(operation_key, provider_transaction_id, status, decline_code)
                                VALUES (?, ?, ?, ?) ON CONFLICT (operation_key) DO NOTHING
                                """,
                        request.operationKey(),
                        request.operationKey(),
                        status.name(),
                        decline == null ? null : decline.name());
        var result =
                jdbc.queryForObject(
                        "SELECT * FROM provider_operations WHERE operation_key = ?",
                        (rs, row) -> {
                            String code = rs.getString("decline_code");
                            return new ProviderResult(
                                    rs.getString("provider_transaction_id"),
                                    PaymentStatus.valueOf(rs.getString("status")),
                                    code == null ? null : DeclineCode.valueOf(code));
                        },
                        request.operationKey());
        return new Outcome(result, inserted == 1);
    }
}
