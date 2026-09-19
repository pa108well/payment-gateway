package dev.gateway.repository;

import dev.gateway.repository.entity.PaymentEntity;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.id = :id")
    Optional<PaymentEntity> lockById(@Param("id") UUID id);

    @Query("select p.id from PaymentEntity p where p.nextAttemptAt <= :now and (p.leaseUntil is null or"
                    + " p.leaseUntil <= :now) order by p.nextAttemptAt")
    List<UUID> findDue(@Param("now") Instant now, Pageable pageable);

    List<PaymentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
