# Implementation plan and acceptance gates

## Scope

Build a modular monolith with an optional in-process event pipeline. A reservation contains one product and quantity. Payments are simulated; no real card data or money is handled. PostgreSQL owns inventory and order correctness. Redis never authorizes a reservation. Kafka delivery is at least once, not exactly once.

## Stages

1. Foundation: Java 21, Spring Boot, Maven wrapper, Flyway, schema constraints. Gate: application starts and database rejects an invalid inventory balance.
2. Inventory: catalog endpoints, pessimistic product locking, reservations. Gate: concurrent demand cannot oversell; failures roll back.
3. Lifecycle: caller-scoped idempotency, cancellation, simulated payment and expiry. Gate: retries return the original order; conflicting retries fail; competing transitions preserve inventory.
4. Events: transactional outbox, Kafka relay, idempotent consumer. Gate: failed publication remains retryable, duplicate events do not duplicate effects, rollback does not leak events.
5. API: user/admin authentication, ownership checks, Redis catalog cache, consistent problem responses. Gate: unauthorized and cross-user requests fail; cache failures cannot corrupt inventory.
6. Delivery: small same-origin demo, Docker Compose, CI, documentation and reproducible load scenarios. Gate: complete test suite and HTTP smoke scenario; document any environment-dependent checks not executed.

## Invariants

- available >= 0, reserved >= 0, sold >= 0
- available + reserved + sold = initial_stock
- Only RESERVED orders can transition to CONFIRMED, CANCELLED, EXPIRED or PAYMENT_FAILED.
- Retrying a terminal action never changes inventory twice.
- Each accepted idempotency key belongs to a single caller and request payload.
- Order state and its outbox event commit in the same database transaction.
- Consumer projections process each event ID at most once in their own database transaction.

## Test environments

Fast tests use H2 in PostgreSQL mode to exercise application behavior. They are not proof of PostgreSQL locking semantics. The PostgreSQL acceptance suite uses Testcontainers and must run on a Docker-capable host/CI. Kafka and Redis behavior must be tested with real service implementations, not asserted from mocks alone.

## Evidence

Stage 1: `mvn test` passed 2 tests on Java 21 with H2. Application startup, Flyway migration and the inventory balance CHECK constraint passed. PostgreSQL remains an explicit integration gate.

Stage 2: `mvn test` passed 8 tests. With H2, 120 concurrent one-unit requests against 25 items produced exactly 25 reservations. Insufficient stock, negative quantity, transaction rollback, price snapshot and ownership checks passed. This is application-level concurrency evidence, not the pending PostgreSQL gate.

Stage 3: `mvn test` passed 18 tests. Thirty concurrent retries produced one order. Conflicting payloads and cross-product key races preserved inventory. Repeated callbacks, cancellation/payment races, expiry/payment races and late payment were exercised.

Stage 4: `mvn test` passed 27 tests. A real embedded KRaft Kafka broker delivered reservation and confirmation events; replay created no duplicate audit receipt. Relay retry/backoff, competing publishers, transactional rollback and concurrent consumer deduplication passed. The consumer records audit receipts, not shipping or payment effects.

Stage 5: all 38 application/API tests passed. The Redis integration suite then passed 3 tests using an actual Redis 7.0.15 process: metadata/TTL, expiration, and catalog fallback after stopping Redis. Customer/admin authorization, CSRF, invalid passwords and cross-user access were verified. The initial Redis harness deadlocked while awaiting asynchronous work in a class initializer; polling on the initializing thread fixed the harness. PostgreSQL and container deployment remain separate gates.

Stage 6: built the interactive demo, HTTP smoke/contention clients, Compose stack, CI gates, architecture/API/interview documentation and PostgreSQL acceptance subclasses. Final local verification passed 41 application/HTTP/Kafka tests and 3 real Redis tests. A running H2-backed HTTP server accepted exactly 25 of 120 competing requests for 25 units. The poison-event test caught and fixed the DLT destination mismatch. PostgreSQL and Docker/Compose execution require the configured Docker-capable CI gate and are not claimed complete; see `validation.md`.
