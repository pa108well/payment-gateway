# Payment Gateway

A small deposit gateway with a minimal, Apple-inspired scenario console. Spring Boot 3.5, Java 21+ (the Docker build and runtime use **Java 25**), Maven, PostgreSQL 17, JPA/Hibernate, Flyway and JUnit 5.

## Run

Prerequisite: Docker with Compose.

```sh
docker compose up --build -d
```

Open **http://localhost:8080**. No separate UI build, account, demo profile or provider configuration is required. The application itself is the test environment. PostgreSQL data survives application restarts.

```sh
docker compose logs -f app
docker compose stop
```

For local Java development (JDK 21+ and Maven 3.6.3+):

```sh
docker compose up -d db
mvn -s .mvn/settings.xml spring-boot:run
```

The checked-in Maven settings file is empty; passing it isolates the build from unrelated user-level repository settings. No private repositories or credentials are required.

## Test

Docker must be running. Tests create and destroy their **own PostgreSQL container**; they never use the application's database. No H2 replacement and no silently skipped integration tests.

```sh
mvn -s .mvn/settings.xml verify
```

For an additional end-to-end restart check with the local Compose app already running:

```sh
python3 scripts/check-restart.py
```

This creates one test payment, restarts **only the app container**, and verifies that the saved soft-decline schedule resumes and succeeds on the third attempt. Python 3 is needed only for this optional check.

Tests use a real Spring HTTP server, the HTTP mock provider and Flyway-migrated PostgreSQL. Network-boundary tests additionally use a small local HTTP server. Short circuiting the persisted due time in retry tests avoids waiting for real backoff intervals without bypassing the claim or state-transition logic.

## API

### Create a deposit

```sh
curl -i http://localhost:8080/api/v1/payments/deposit \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: abc-123' \
  -H 'X-Provider-Scenario: SOFT_THEN_SUCCESS' \
  -d '{"merchantId":"M001","orderId":"ORD-1001","amount":100.50,"currency":"EUR","paymentMethod":"CARD"}'
```

`X-Provider-Scenario` is optional and defaults to `SUCCESS`. The body remains the assignment's contract. Supported currencies are explicitly **EUR, USD, GBP**, all with two fractional digits. Amounts are positive `BigDecimal` / `NUMERIC(19,2)`, never floating point in the gateway. The UI caps interactive amounts below one billion to stay within its JavaScript display precision; the API supports up to 17 integral digits.

The response contains `transactionId`, `status`, the original payment fields, current `providerTransactionId`, `declineCode`, `attemptCount`, `nextAttemptAt`, `createdAt`, and `updatedAt`. The `Location` header identifies the detail endpoint.

| HTTP | Meaning |
| --- | --- |
| 201 | A payment resource was created, even if its business outcome is FAILED or still unresolved |
| 200 | Idempotent replay (current payment state), successful/duplicate webhook or read |
| 400 | Invalid input, missing key, unknown enum, malformed JSON or unsupported webhook status |
| 404 | Payment/provider operation not found |
| 409 | Key reused with different input, or invalid status transition |
| 503 | Database access/transaction failed; retry using the **same** idempotency key |
| 500 | Unexpected internal error; no implementation details exposed |

Every response has an `X-Request-Id`. Error bodies consistently contain `code`, `message`, `requestId`, `timestamp` and `fields` (an object of validation errors, empty otherwise). API responses disable caching.

### Read and webhook

```sh
curl http://localhost:8080/api/v1/payments
curl http://localhost:8080/api/v1/payments/TRANSACTION_UUID
curl -i http://localhost:8080/api/v1/payments/webhook \
  -H 'Content-Type: application/json' \
  -d '{"providerTransactionId":"P-TRANSACTION_UUID-1","status":"SUCCESS"}'
```

Use the actual provider ID from the deposit response. Valid webhook statuses: `PENDING`, `SUCCESS`, `FAILED`. The detail endpoint returns `{ "payment": {...}, "events": [...] }`. Health: `/actuator/health`.

## Correctness and tradeoffs

### Idempotency and concurrency

The database enforces `UNIQUE(merchant_id, idempotency_key)`. Registration uses PostgreSQL `INSERT ... ON CONFLICT DO NOTHING` within a short transaction. A conflicting insertion waits for the winner to commit; the following READ COMMITTED query sees that row. No exception-and-query inside an already-aborted transaction, and no process-local lock.

A SHA-256 fingerprint covers canonicalized business fields **and the scenario**. Decimal representations such as `100.50` and `100.5` are equivalent. JSON encoding prevents ambiguous delimiter concatenation. An existing key with a different fingerprint returns 409. Keys are scoped to merchants; `orderId` is deliberately **not unique**. Different keys represent different payments even for the same order. Keys are retained indefinitely in this exercise.

A replay returns the **current resource state**, not a frozen copy of the first HTTP response. Concurrent callers may observe PROCESSING before the first caller finishes. They must not interpret it as failure or submit another key.

### Transaction boundaries and durable recovery

1. Commit payment registration and its creation event.
2. Lock the payment row briefly and acquire a persisted lease/token; allocate an attempt if needed.
3. **Call the provider over HTTP outside the database transaction.**
4. Lock the row again and commit the outcome, retry schedule and audit event atomically.

No database connection/row lock is held while waiting for the provider. A scheduled worker scans due payments. The row lock serializes claims, and a 15-second lease prevents active workers from owning the same operation. A unique token fences late responses from workers whose lease expired or whose operation was resolved by a webhook. A crash leaves durable work that is reclaimed after lease expiry.

A definitive soft decline allocates a **new operation key** on its next attempt. A timeout, failed response save or crash reuses the **same operation key**. The mock provider's unique operation ledger commits separately and returns the original result for repeated keys. This handles the critical “provider charged, gateway commit failed” window. The provider ID is derived from the payment UUID and attempt number before sending, which also lets an early webhook correlate immediately. This is an explicit mock-provider contract; real integrations must adapt to their provider's idempotency/correlation support.

The design does **not** claim distributed exactly-once delivery. It uses at-least-once execution plus durable deduplication at both boundaries. The mock is an HTTP endpoint in the same process and owns separate tables in the same database to keep the exercise small. No Kafka, Redis, message broker, separate frontend build or outbox dispatcher is needed for this scope.

### Status and webhook policy

```text
PROCESSING ── accepted ───────────────────────→ SUCCESS
     │       definitive hard decline ────────→ FAILED
     ├── asynchronous acceptance ────────────→ PENDING
     ├── ambiguous network outcome ──────────→ UNKNOWN
     └── soft decline with budget ───────────→ RETRY_SCHEDULED
                                                   │ due
                                                   └────→ PROCESSING (new attempt)
UNKNOWN ── recovery ──→ same provider operation, never a new charge
PENDING / UNKNOWN / PROCESSING ── webhook ──→ PENDING / SUCCESS / FAILED
```

`SUCCESS` and `FAILED` are terminal. Pending payments await a webhook; they are not automatically charged again. Unknown outcomes are never converted to FAILED merely because time elapsed. Recovery replays the same operation every 3 seconds until a definitive result arrives; unresolved outcomes do not consume the three-decline attempt budget.

Webhooks lock the payment row and validate the **specific attempt**. Repeating the same status returns 200 without another event or timestamp update. Historical duplicate declines are acknowledged without changing a later attempt. Contradictory updates to completed attempts return 409. A final webhook clears any lease, so an older in-flight HTTP response cannot overwrite it. An early PENDING webhook still permits a final provider reply, but a subsequent HTTP timeout cannot downgrade that acknowledged PENDING state. With the supplied webhook schema, FAILED is a final outcome; automated soft-decline retries are driven by provider responses containing an explicit decline code.

### Declines and retries

| Code/scenario | Policy |
| --- | --- |
| INSUFFICIENT_FUNDS | Soft; retry within the three-attempt budget |
| ISSUER_UNAVAILABLE | Soft; retry within the three-attempt budget |
| PROCESSING_ERROR | Soft; definitive transient refusal, retry |
| EXPIRED_CARD, INVALID_CARD, LOST_CARD, STOLEN_CARD | Hard; immediate FAILED |
| DO_NOT_HONOR | Conservative hard-decline policy; no blind automatic retries |
| SOFT_THEN_SUCCESS | ISSUER_UNAVAILABLE twice, then SUCCESS |
| FAILED | Alias for definitive DO_NOT_HONOR |
| TIMEOUT | Accepted SUCCESS with the first HTTP reply delayed past the client deadline |

Three **total** provider attempts: initial attempt, retry after 3 seconds, retry after 6 seconds. HTTP replays of the same uncertain operation do not count as new attempts. Exhausted definitive soft declines become FAILED. Delays are intentionally short for interactive testing. In real payment integrations, retry eligibility and timing depend on processor/network advice; insufficient funds would generally need a longer schedule. These classifications are this mock's explicit policy, not universal card-network rules. Reason names are informed by [Adyen's refusal documentation](https://docs.adyen.com/development-resources/refusal-reasons).

### Storage and configuration

Flyway's `V1__payments.sql` creates payments, per-attempt history, events, and the mock provider ledger. It includes the merchant/key unique constraint, positive amount and allowed currency/method/status checks, provider ID uniqueness, a partial due-work index and event/list indexes. Hibernate only validates schema; it never creates or alters it.

| Property | Default |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/payments` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `payments` / `payments` (local test credentials) |
| `PORT` | `8080` |
| `payment.max-attempts` | 3 (supported range 1–3) |
| `payment.retry-delay` | 3s (exponential per definitive declined attempt) |
| `payment.recovery-delay` | 3s |
| `payment.provider-timeout` | 800ms |
| `payment.lease-duration` | 15s |
| `payment.worker-delay-ms` | 1000 |