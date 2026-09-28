# Inventory Reservation Service

A Java 21 / Spring Boot backend for limited inventory: reserve stock without overselling, retry requests safely, and deliver order events through a transactional outbox. Includes an interactive same-origin demo.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Security · JPA/Hibernate · PostgreSQL · Flyway · Redis · Kafka · Micrometer/Prometheus · JUnit 5 · Testcontainers · Docker Compose · GitHub Actions.

**Verified:** 70 automated tests plus a complete PostgreSQL/Redis/Kafka Compose smoke and HTTP contention check passed in [GitHub Actions](https://github.com/Parkryan0128/inventory-reservation-service/actions/runs/36461635023). See [validation evidence](docs/validation.md).

## Run the demo

Requires Docker with Compose v2. From the repository root:

```bash
docker compose up --build
```

Open **http://localhost:8080**. Log in as `admin`, create a product, disconnect, then log in as `alice` and reserve it. Replay the request to observe the same order ID. Switch back to `admin` to simulate successful or failed payment. The demo TTL is 2 minutes; unpaid reservations expire automatically.

| Local demo account | Password | Access |
| --- | --- | --- |
| `alice` | `demo-alice-password` | Own orders, catalog |
| `bob` | `demo-bob-password` | Own orders, catalog |
| `admin` | `demo-admin-password` | Catalog writes, all recent orders, simulated payments, metrics |

These credentials belong only to the explicit `demo` profile. Compose binds the app to **127.0.0.1** and does not publish database, Redis or Kafka ports. `.env.example` documents local overrides.

Enable the complete event pipeline:

```bash
docker compose -f compose.yml -f compose.events.yml --profile events up --build
python3 scripts/smoke.py --wait 150 --require-events
```

The Kafka broker has a persistent volume and three event partitions. This is a **single-broker demo**, not a highly available cluster. Base Compose leaves Kafka disabled; order/outbox transactions still commit and pending events wait until the relay is enabled.

Stop without deleting data: `docker compose -f compose.yml -f compose.events.yml --profile events down`. Add `-v` only to intentionally reset **all demo data**.

## What this demonstrates

- A database row lock and inventory CHECK constraints prevent overselling.
- `(authenticated owner, Idempotency-Key)` uniquely identifies a reservation request. Matching retries return the same order's **current** state; changed product/quantity returns `409`.
- Payment, cancellation and expiration compete through the same locked state transition. Late payment returns an `EXPIRED` order and does not sell stock.
- Order state and an outbox record commit together. The relay marks delivery only after a Kafka acknowledgment and retries failures with backoff.
- The consumer stores audit receipts transactionally and deduplicates by event ID. Invalid events are retried and routed to `orders.v1.DLT`.
- Redis caches **only catalog metadata** for 30 seconds. Inventory and the order price snapshot always come from the database. Cache failures fall back to the database.
- Spring Security enforces customer/admin access, authenticated ownership, BCrypt password verification and CSRF protection. The UI stores credentials only in memory.

Read [architecture and tradeoffs](docs/architecture.md), [API contract](docs/api.md), [implementation gates](docs/implementation-plan.md), and [validation evidence](docs/validation.md).

## Tests

Java 21 is required; Maven is provided by the wrapper. The demo session tests use Node.js 22 or newer and have no npm dependencies. Browser acceptance uses Playwright against a running demo.

```bash
npm test         # Demo session isolation, cancellation and error responses
./mvnw test       # H2 application/API/HTTP tests + a real embedded Kafka broker
./mvnw verify     # Above + PostgreSQL and Redis Testcontainers; requires Docker
```

To run the browser journey against the Compose app:

```bash
npm ci
npx playwright install chromium
npm run test:browser
```

It checks product creation, reservation/replay, account isolation, payment, and a delayed response during account switching. CI runs it against the complete Compose stack.

Java formatting is checked during the Maven build. Run `./mvnw spotless:apply` after editing Java; `.editorconfig` defines whitespace for the other files.

`verify` intentionally **fails** when Docker is unavailable. PostgreSQL tests re-run the actual inventory, lifecycle, schema and outbox acceptance cases against PostgreSQL 16. H2 tests are not evidence of PostgreSQL locking behavior. CI also builds the application image and runs an HTTP smoke test against the complete Compose stack. See the validation document for which gates have actually run.

For a Docker-less machine that already has Redis, the Redis acceptance suite can use a local executable:

```bash
TEST_REDIS_BINARY="$(command -v redis-server)" ./mvnw -Dit.test=RedisIntegrationIT verify
```

This explicit selection omits the PostgreSQL gate; it is not a substitute for the default CI command. Test results live in `target/surefire-reports` and `target/failsafe-reports`; coverage is in `target/site/jacoco/index.html`.

## Exercise concurrency over HTTP

Against your own running local demo:

```bash
python3 scripts/smoke.py
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
```

The contention script creates a fresh product, checks the stock invariant, and exits nonzero for unexpected responses or incorrect success counts. It prints measured status counts, throughput and p50/p95 latency. Results include HTTP Basic/BCrypt authentication, client overhead and your machine's limits; they are **not a storage-engine benchmark**. No unmeasured throughput claims are made here.

## Local development without the app container

```bash
docker compose up -d postgres redis
```

The provided Compose intentionally keeps infrastructure ports private. To run Java on your host, use your own local PostgreSQL/Redis instances or an explicitly configured loopback-only port override. Set `DATABASE_URL` (a JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`, and `ALICE_PASSWORD`, `BOB_PASSWORD`, `ADMIN_PASSWORD` (at least 12 characters), then run `./mvnw spring-boot:run`. Set `CACHE_ENABLED=true`/`REDIS_HOST` and, optionally, `EVENTS_ENABLED=true`/`KAFKA_BOOTSTRAP_SERVERS`. Normal reservation TTL is 15 minutes; `APP_RESERVATION_TTL=PT2M` overrides it.

## Boundaries

One product per order; simulated payments only; three configured in-memory identities; one application process for HTTP, scheduled workers and the consumer. This is a focused portfolio service, not a production commerce platform. Public deployment needs HTTPS, strong credentials, rate limiting, an identity provider, monitoring/backups and a retention policy for orders/outbox/receipts. Do not publish the local demo passwords or expose the simulator to untrusted users. Shared CSRF session storage or sticky routing is needed before adding API replicas.
