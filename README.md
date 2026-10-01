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
- Redis caches product metadata for 30 seconds and falls back to PostgreSQL on failure. Stock and order prices always come from PostgreSQL.

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

## API

The local API uses HTTP Basic authentication with `alice`, `bob`, and `admin`; default passwords are in `.env.example`. Writes also require the session cookie and CSRF header returned by `GET /api/csrf`. Customers can access only their own orders.

| Endpoint | Purpose |
| --- | --- |
| `GET /api/me` | Current account and roles |
| `GET /api/products`, `GET /api/products/{id}` | Products and current stock |
| `POST /api/products` | Create a product (admin) |
| `GET /api/catalog/{id}` | Cached product metadata |
| `POST /api/orders` | Reserve stock; requires `Idempotency-Key` |
| `GET /api/orders`, `GET /api/orders/{id}` | View orders |
| `POST /api/orders/{id}/cancel` | Cancel a reservation |
| `POST /api/admin/payments/{id}` | Simulate payment with `{"success":true}` or `false` |
| `GET /api/admin/orders`, `GET /api/admin/status` | Orders and event delivery status (admin) |
| `GET /actuator/health`, `GET /actuator/prometheus` | Health and metrics; metrics require admin |

Reserve with `{"productId":"<uuid>","quantity":2}`. Matching retries return the same order ID and its current status. Quantities and prices are integers; prices use cents. Errors return HTTP status codes and a JSON `code`, such as `INSUFFICIENT_STOCK` or `IDEMPOTENCY_CONFLICT`.

The public deployment exposes only the demo and health endpoint. Its writes require CSRF protection and the configured hostname; normal customer/admin APIs are closed.

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

Tests cover concurrent stock updates, retries, order transitions, PostgreSQL constraints, Kafka delivery, Redis outages, access control, and the demo UI. CI runs these checks and the production stack over HTTPS before publishing an image.

## Deploy

The VPS uses Docker Compose and Caddy for HTTPS. Point the hostname to the VPS and allow ports 80/443. After the commit's CI build succeeds, run in the server checkout:

```bash
bash deploy/init-env.sh inventory.example.com
bash deploy/up.sh
```

Credentials are generated in the Git-ignored `deploy/.env.production`. Images come from GHCR; no build runs on the VPS. Named volumes preserve database and Kafka data, so do not remove them to update the app.

Automatic deployment uses `deploy/install-cd-keys.sh` from `~/apps/inventory-reservation-service` as `ubuntu`. Configure GitHub secrets `DEPLOY_SSH_KEY` and `DEPLOY_KNOWN_HOSTS`, plus variables `DEPLOY_HOST`, `DEPLOY_USER`, and `DEPLOY_ENABLED=true`. Each main push must pass CI before the Deploy workflow updates the VPS.

The Caddy proxy is shared with OptiRoute through `portfolio-edge`. Its additional site configuration lives in Git-ignored `deploy/proxy/sites/*.local.caddy`. Deployment scripts serialize updates with a shared lock.

Manual update and rollback:

```bash
git pull --ff-only origin main
bash deploy/up.sh
# Restore the previous image if it supports the current database schema:
bash deploy/up.sh "$(cat deploy/.previous-image)"
```
