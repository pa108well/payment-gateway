CREATE TABLE payments
(
    transaction_id          uuid PRIMARY KEY,
    merchant_id             varchar(64)    NOT NULL,
    order_id                varchar(128)   NOT NULL,
    amount                  numeric(19, 2) NOT NULL CHECK (amount > 0),
    currency                varchar(3)     NOT NULL CHECK (currency IN ('EUR', 'USD', 'GBP')),
    payment_method          varchar(16)    NOT NULL CHECK (payment_method = 'CARD'),
    status                  varchar(24)    NOT NULL CHECK (status IN
                                                           ('PROCESSING', 'PENDING', 'UNKNOWN', 'RETRY_SCHEDULED',
                                                            'SUCCESS', 'FAILED')),
    idempotency_key         varchar(128)   NOT NULL,
    request_hash            varchar(64)    NOT NULL,
    provider_transaction_id varchar(64) UNIQUE,
    scenario                varchar(40)    NOT NULL,
    decline_code            varchar(40),
    attempt_count           integer        NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    next_attempt_at timestamptz,
    lease_until timestamptz,
    lease_token             uuid,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_payment_idempotency UNIQUE (merchant_id, idempotency_key)
);
CREATE INDEX ix_payments_recent ON payments (created_at DESC);
CREATE INDEX ix_payments_due ON payments (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
CREATE TABLE payment_attempts
(
    provider_transaction_id varchar(64) PRIMARY KEY,
    transaction_id          uuid        NOT NULL REFERENCES payments (transaction_id),
    attempt_number          integer     NOT NULL,
    status                  varchar(24) NOT NULL,
    UNIQUE (transaction_id, attempt_number)
);
CREATE TABLE payment_events
(
    id bigserial PRIMARY KEY,
    transaction_id uuid         NOT NULL REFERENCES payments (transaction_id),
    status         varchar(24)  NOT NULL,
    message        varchar(255) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX ix_payment_events ON payment_events (transaction_id, id);
CREATE TABLE provider_operations
(
    operation_key           varchar(64) PRIMARY KEY,
    provider_transaction_id varchar(64) NOT NULL UNIQUE,
    status                  varchar(16) NOT NULL,
    decline_code            varchar(40),
    created_at timestamptz NOT NULL DEFAULT now()
);
