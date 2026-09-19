package dev.gateway.scheduler;

import dev.gateway.service.PaymentService;
import dev.gateway.service.PaymentStore;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "payment.worker-enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Log4j2
public class RecoveryWorker {

    PaymentStore store;

    PaymentService service;


    @Scheduled(fixedDelayString = "${payment.worker-delay-ms:1000}")
    public void recover() {
        try {
            for (var id : store.due()) {
                try {
                    service.process(id);
                } catch (RuntimeException error) {
                    log.error(
                            "Recovery failed for payment {}. Lease will expire for a later retry.",
                            id,
                            error);
                }
            }
        } catch (RuntimeException error) {
            log.error("Could not load recovery queue; next scan will retry", error);
        }
    }
}
