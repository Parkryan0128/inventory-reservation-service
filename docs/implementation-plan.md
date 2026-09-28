# Implementation plan and acceptance gates

## Scope

Build a modular monolith with a separate optional event-processing role. A reservation contains one product and quantity. Payments are simulated; no real card data or money is handled. PostgreSQL owns inventory and order correctness. Redis never authorizes a reservation. Kafka delivery is at least once, not exactly once.

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
