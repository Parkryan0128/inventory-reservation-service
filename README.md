# Inventory Reservation Service

An inventory reservation service built with Java 21, Spring Boot, PostgreSQL, Redis, and Kafka.

[Live demo](https://inventory.ryanparkdev.com/)

## How it works

```text
Buy → RESERVED
        ├── Payment succeeds → CONFIRMED
        ├── Payment fails    → PAYMENT_FAILED
        ├── Customer cancels → CANCELLED
        └── 2 minutes pass   → EXPIRED
```

- Reservations lock the product row and save the stock change, order, and outbox event in one PostgreSQL transaction.
- Idempotency keys prevent duplicate reservations. Reusing a key with a different product or quantity returns a conflict.
- Payment, cancellation, and expiry lock the order and product so stock changes once. The database enforces `available + reserved + sold = initial_stock`.
- An outbox relay publishes order events to Kafka with retries. An audit consumer deduplicates events; delivery is at least once.
- The catalog API uses Redis to cache product metadata for 30 seconds, falling back to PostgreSQL on failure. The public demo reads PostgreSQL directly and runs without Redis.

Each order contains one product. Payments are simulated.

The demo has five scenarios: concurrent reservations, duplicate requests, payment versus cancellation, the order lifecycle, and expiry. Scenarios execute real backend operations, then replay the recorded results. The contention scenario uses 100 service calls across 16 workers for five units; the expiry scenario advances its test order's deadline.

Manual mode lets visitors add/remove stock and reserve the same shared product. Each visitor can pay or cancel only their own orders. Stock and orders persist across restarts; browser sessions and the last 200 activity entries are held in memory. The demo runs as one application instance.

## Project structure

```text
src/main/java/dev/inventory/  Application code
src/main/resources/          Configuration, SQL migrations, and demo UI
src/test/                    Java, JavaScript, and browser tests
scripts/                     HTTP and deployment checks used by CI
deploy/                      Production Compose, HTTPS proxy, and deployment scripts
```

## Run locally

Requires Docker with Compose.

```bash
docker compose up --build -d
```

Open [localhost:8080](http://localhost:8080/). This starts the application, PostgreSQL, Redis, and Kafka. Local overrides are in [.env.example](.env.example).

## Tests

Requires Java 21, Node.js 22+, and Docker. The browser and HTTP checks use the running local stack.

```bash
./mvnw verify
npm ci
npm test
npx playwright install chromium
npm run test:browser
python3 scripts/smoke.py --require-events
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
```

Tests cover concurrent stock updates, retries, order transitions, PostgreSQL constraints, Kafka delivery, Redis outages, access control, and the demo UI. GitHub Actions runs these checks on each push.

## Contact

- **Name:** Ryan Park
- **Email:** [parkryan0128@gmail.com](mailto:parkryan0128@gmail.com)
- **LinkedIn:** [linkedin.com/in/parkryan0128](https://www.linkedin.com/in/parkryan0128)
- **GitHub:** [github.com/Parkryan0128](https://github.com/Parkryan0128)
