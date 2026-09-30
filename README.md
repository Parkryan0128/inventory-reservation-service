# Java Inventory Reservation Service

An inventory reservation service built with Java 21 and Spring Boot.

PostgreSQL handles stock and order transactions. Redis caches product metadata, and Kafka delivers order events through a transactional outbox.

[Inventory Playground](docs/demo.md) · [Architecture](docs/architecture.md) · [API documentation](docs/api.md)

## How it works

```mermaid
stateDiagram-v2
    direction TB
    [*] --> RESERVED
    RESERVED --> CONFIRMED: Payment succeeds
    RESERVED --> CANCELLED: Customer cancels
    RESERVED --> PAYMENT_FAILED: Payment fails
    RESERVED --> EXPIRED: Reservation expires
```

A reservation locks the product row and saves the stock change, order, and outbox event in one transaction. Retrying with the same customer, idempotency key, and payload returns the existing order.

Payment, cancellation, and expiry share a locked transition so stock changes only once. The database enforces `available + reserved + sold = initial_stock`.

The Kafka relay retries failed deliveries, and the consumer deduplicates audit events. Redis is used only for metadata; inventory and order prices come from PostgreSQL.

Each order contains one product. Payments are simulated.

## Project structure

| Path | Contents |
| --- | --- |
| `src/main/java/` | Inventory, orders, security, cache, events, and demo scenarios |
| `src/main/resources/` | Configuration, database migrations, and playground UI |
| `src/test/` | Java, JavaScript, and browser tests |
| `scripts/` | HTTP smoke and contention checks |
| `docs/` | Architecture, API reference, and validation results |

## Run the playground

Requires Docker with Compose v2.

```bash
docker compose up --build -d
```

Open [127.0.0.1:8080](http://127.0.0.1:8080/) and click **Send 100 customers**. The single-page playground shows a fictional graphics card with five units, 100 customer responses, and the stock moving from available to reserved. Click a customer square to inspect its actual response and order ID. No login or manual product setup is needed.

Switch presets to try **Payment vs cancel**, **Duplicate request**, **Checkout & returns**, or **Abandoned checkout**. After execution, step through or replay the recorded database snapshots. Technical assertions and raw responses are expandable rather than the main experience.

The flash sale submits 100 reservation service calls through 16 server worker threads for five units. It expects five reservations and 95 insufficient-stock rejections. Response squares are ordered by request number, not completion order. This is not a benchmark of 100 simultaneous HTTP connections. Replay uses real snapshots from the completed run, not invented transaction timing. The expiry preset advances only its generated order's deadline to avoid waiting two minutes.

The demo is bound to localhost and enabled only under the `demo` profile. Do not expose this profile publicly. Configuration overrides are in [.env.example](.env.example); detailed behavior and test setup are in the [playground guide](docs/demo.md).

The original [manual API workspace](http://127.0.0.1:8080/index.html) remains available for account/ownership testing:

| Local account | Password |
| --- | --- |
| `alice` | `demo-alice-password` |
| `bob` | `demo-bob-password` |
| `admin` | `demo-admin-password` |

Use `admin` to create products and simulate payments, or `alice` and `bob` to reserve stock and view their own orders. Unpaid reservations expire after two minutes.

To enable Kafka delivery:

```bash
docker compose -f compose.yml -f compose.events.yml --profile events up --build -d
```

## Tests

Java tests require Java 21; JavaScript tests require Node.js 22 or newer.

```bash
./mvnw test      # Application and demo tests, including embedded Kafka
./mvnw verify    # Also runs PostgreSQL and Redis tests; requires Docker
npm test        # Session, response validation and playground logic
```

Tests cover concurrent reservations, retries, order transitions, event delivery, cache outages, access control, and the playground's isolation and error handling. Browser tests exercise the real scenarios, inspect individual responses, verify snapshot replay without new writes, and check mobile layout. See the [playground guide](docs/demo.md) for browser setup and [validation results](docs/validation.md) for earlier recorded acceptance runs.

## HTTP concurrency checks

With the demo running and Python 3 installed:

```bash
python3 scripts/smoke.py
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
```

This separate HTTP check uses 120 requests and 25 units. It checks the final inventory and reports response counts and latency. The browser's five-unit flash-sale preset is a different workload.

## Contact

- **Name:** Ryan Park
- **Email:** [parkryan0128@gmail.com](mailto:parkryan0128@gmail.com)
- **LinkedIn:** [linkedin.com/in/parkryan0128](https://www.linkedin.com/in/parkryan0128)
- **GitHub:** [github.com/Parkryan0128](https://github.com/Parkryan0128)
