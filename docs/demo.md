# Inventory Playground

Start Docker Desktop and run from the repository directory:

```bash
docker compose up --build -d
```

Open `http://127.0.0.1:8080/`. No account, password, product setup or second browser is needed.

## Start with the last five

The first screen shows Vector One, a fictional graphics card, and five available units. Click **Send 100 customers**. The backend creates a fresh product and submits 100 reservation calls, one per generated customer, using 16 server workers. The UI displays one square per actual response: five reservations and 95 insufficient-stock conflicts are the expected result. Selecting a square reveals its response code, measured service-call duration and returned order ID.

The inventory panel separates **Available**, **Reserved** and **Sold**. Five reservations means five units are being held for payment, not five completed purchases. Small blocks visualize units in each state; counts and text remain available without relying on color.

## Other situations

The three primary presets are **Flash sale**, **Payment vs cancel**, and **Duplicate request**. Two secondary presets cover **Checkout & returns** and **Abandoned checkout**. Switching a preset is a preview only. The large action button executes that situation; it does not run an unrelated test suite.

| Preset | Actual backend operation | Expected result |
| --- | --- | --- |
| Flash sale | 100 reservation calls for distinct generated customers; 16 workers; stock 5 | 5 orders, 95 stock conflicts, 0 available / 5 reserved / 0 sold |
| Payment vs cancel | Reserve one unit, then race payment and cancellation with 2 workers | One terminal transition wins, the other conflicts; inventory matches the winner |
| Duplicate request | 16 concurrent identical reservations for 3 units, then reuse the key with quantity 4 | One persisted order and 3 held units; the changed payload is rejected |
| Checkout & returns | Purchase, cancellation and payment failure, repeating each terminal operation | Stock moves once per transition; final inventory 9 available / 0 reserved / 3 sold |
| Abandoned checkout | Reserve 2 units, advance only this generated order's deadline, invoke normal expiry twice | Both held units return once; final inventory 5 available / 0 reserved / 0 sold |

The initial figures are labelled **Preset**, not measured stock. When the response returns, **Recorded** snapshots replace the preview. Each run creates new isolated data; earlier products and customer orders are not reset.

## Follow the stock

After execution, click the recorded snapshot steps to see the exact inventory captured at that point. **Replay stock changes** steps through those same snapshots at a presentation pace; it sends no API writes and can be paused. Changing presets or starting another run stops the replay and clears the previous recording.

This is not a fabricated animation or live database trace. Concurrent response squares are shown in request-number order, **not** completion or commit order. Their durations measure the service invocation, including its transaction, not HTTP latency or separately instrumented lock-wait time. Intermediate stock states that were not captured are not invented. Results are historical snapshots: the normal scheduler can subsequently expire unpaid demo reservations.

**Under the hood** contains the execution note, persisted order count, assertions, duration, product ID, completion time and raw response. Infrastructure errors are not classified as stock conflicts, and a failed assertion never produces a success headline. A failed or timed-out response does not imply all database work was rolled back; another run creates a fresh fixture.

## Scope and safety

The demo invokes the same Spring-proxied transactional services as the ordinary API. There are 100 service calls and 16 worker threads in the flash sale, not 100 simultaneous browser connections. Database parallelism is also bounded by the connection pool. `scripts/contention.py` remains available for separate end-to-end HTTP measurements.

Expiry advances only its own generated order's deadline, not the global clock. It exercises the normal expiry service without waiting for the scheduler. Payments are simulated; no money is charged.

Demo controllers and their public security chain exist only under the `demo` profile. POSTs still require a CSRF token, retrieved automatically. Hostnames must be loopback names, and Compose binds to `127.0.0.1`. This intentionally unauthenticated feature is for **local use only**; the hostname check is not public-deployment authentication. Do not expose the demo profile to the internet.

Fixed presets bound the workload. Server-side exclusion and disabled browser controls prevent overlapping runs. The worker guard releases on failure only after workers terminate. Generated rows remain in the local database.

The original account-based manual workspace remains at `/index.html`; normal `/api/products`, `/api/orders` and `/api/admin/**` authentication, ownership, roles and CSRF protections are unchanged. No credentials are embedded in the playground.

## Optional events

Kafka is not required to run these scenarios. The expandable backend details report application-wide outbox/audit counts, not a per-scenario event guarantee. Enable delivery with:

```bash
docker compose -f compose.yml -f compose.events.yml --profile events up --build -d
```

## Tests

```bash
npm test
./mvnw test
./mvnw verify
npm ci
npx playwright install chromium
npm run test:browser
```

`verify` runs the Java scenario tests against real PostgreSQL through Testcontainers as well as the default H2 tests. Assertions independently inspect persisted stock and orders, recorded request histograms, timing field validity, fresh fixtures, isolated expiry, competing transitions and security boundaries. JavaScript tests cover execution guards, transport errors, record validation and summaries. Browser acceptance runs each preset against Compose, inspects returned order IDs, replays snapshots without writes, verifies failures and retry behavior, checks double-click exclusion and mobile layout, and retains desktop/mobile screenshots as CI artifacts.
