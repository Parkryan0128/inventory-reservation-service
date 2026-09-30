# One-click reservation lab

Start Docker Desktop, then run from the repository directory:

```bash
docker compose up --build -d
```

Open `http://127.0.0.1:8080/` and click **Run all 5 checks**. No account or password is needed. Each card also has a **Run this check** button. The page shows measured request outcomes, database inventory snapshots, assertions, elapsed time and the raw response. A failed assertion is a FAIL; an infrastructure error is an ERROR, not an out-of-stock result. Results reset on page reload rather than showing old results as a new run.

## Scenarios

| Check | Workload | Expected result |
| --- | --- | --- |
| Concurrent reservations | 120 reservation service calls; 16 server worker threads; stock 25 | 25 accepted, 95 insufficient-stock conflicts, 25 database order rows |
| Idempotency | 16 concurrent identical requests, followed by one changed payload | One order, only 3 units reserved, changed payload rejected |
| Lifecycle | Reserve, confirm, cancel and fail payments; repeat each terminal operation | Stock moves once, ending with 9 available, 0 reserved and 3 sold |
| Payment/cancellation race | Two worker threads target the same reserved unit | One transition wins and the other conflicts; stock matches the winner |
| Expiry | Move only the generated order's deadline into the past, then invoke normal expiry twice | EXPIRED, all reserved stock restored once |

The concurrency runner calls the same Spring-proxied transactional services used by the HTTP controllers. It does **not** simulate results in JavaScript. It also does **not** claim 120 simultaneous HTTP connections: there are 120 calls and 16 worker threads, with database concurrency limited by the connection pool. For separate end-to-end HTTP contention measurements, use `scripts/contention.py`.

The expiry fixture skips the two-minute wait by updating only its own generated order's deadline. It exercises the real expiry service, not the scheduler's passage of wall-clock time. The application scheduler remains unchanged.

Every run creates new products and isolated `demo-<uuid>` owners. Existing products, customer orders and global clocks are not reset. Generated rows remain in the local database and normal expiration still applies to unpaid demo reservations. Snapshots report the state when each check completed, not a permanently live view of that product.

## Events

Outbox and audit counts refresh independently and include all application orders. They are observations, not a sixth passed check. Kafka is disabled by default; pending events are retained. Enable actual Kafka delivery with:

```bash
docker compose -f compose.yml -f compose.events.yml --profile events up --build -d
```

## Security and scope

The `/api/demo/**` controllers and their public security chain exist only with the `demo` profile. Requests must use a loopback hostname, and POST requests still require a valid session CSRF token, obtained automatically by the UI. Fixed scenario names prevent arbitrary product IDs or unbounded workload parameters. A server-side gate permits only one scenario at a time and releases on failure; browser controls also prevent double clicks. Worker tasks are cancelled on failure, and the runner waits for worker termination before releasing the gate.

Keep this intentionally unauthenticated demo local. Compose binds to `127.0.0.1`; the hostname check is an additional safeguard, not authentication for a public deployment. Do not expose the demo profile to the internet.

The original account-based workspace is still available at `/index.html`. Its ordinary `/api/products`, `/api/orders` and `/api/admin/**` endpoints retain their authentication, role, ownership and CSRF checks. No credentials are embedded in the new JavaScript.

## Tests

```bash
npm test
./mvnw test
./mvnw verify
npm run test:browser
```

The Java scenario tests run against H2 and, during `verify`, real PostgreSQL through Testcontainers. They independently inspect persisted rows and stock, repeat the payment/cancellation race, test isolation of the expiry fixture, and verify CSRF, the local-host guard, workload exclusion and the absence of demo beans outside the demo profile. JavaScript tests cover orchestration, duplicate-click prevention and error handling. Playwright runs the full unauthenticated journey against Compose, along with repeat runs, error/failure rendering, mobile layout and the original manual workspace.
