# Inventory Reservation Dashboard

```bash
docker compose up --build -d
```

Open `http://127.0.0.1:8080/`. Choose a scenario and click **Run scenario**. No login, account switching or manual product setup is needed.

The purchase flow explains how buying reserves stock, payment confirms it, and cancellation, failed payment or expiry releases it. The demo profile uses a two-minute hold by default; `RESERVATION_TTL` can override it. Expiry is a separate `EXPIRED` state, not a customer cancellation.

Each scenario explains the situation, how the backend handles it and what to look for in the log. The original Backend activity terminal retains its playback, speed, filter and follow controls. Inventory counters accompany a compact result section; expand **Checks** for assertions. Failed checks open automatically. The normal account-based workspace remains at `/index.html`.

## Scenarios

| Scenario | Workload | Expected result |
| --- | --- | --- |
| Concurrent reservations | 100 distinct generated customers, 16 server workers, one unit per call, stock 5 | 5 reservations, 95 insufficient-stock rejections, 5 order rows; stock 0 available / 5 reserved / 0 sold |
| Duplicate requests | 16 concurrent identical calls for 3 units; one changed payload using the same key | 16 replies with one order ID; changed quantity rejected; stock 7 / 3 / 0 |
| Payment vs cancel | Reserve one unit, then race confirmation against cancellation with 2 workers | One transition wins and one is rejected; stock matches the winner |
| Order lifecycle | Three orders; repeat confirmation, cancellation and payment failure | Repeated operations do not move stock again; final stock 9 / 0 / 3 |
| Reservation expiry | Hold 2 of 5 units; try early expiry; advance the generated deadline; expire and retry | NO_CHANGE, EXPIRED, NO_CHANGE; both held units returned once |

## Reading the terminal

Each log entry is recorded on the server. Concurrent workers append their results after the real Spring-proxied transactional service returns. Entries include a monotonically increasing sequence, UTC observation timestamp, request ID, actor label, operation, returned status, measured service-call duration, quantity, idempotency key and returned order ID. Hover over a row to inspect the full order ID.

The server returns the completed recording; the browser reveals it progressively. **Replaying recorded results** identifies this presentation phase. It is not a live WebSocket/SSE stream, HTTP access log, SQL trace, lock-wait profiler or reconstructed database commit order. Service calls are not labelled with invented HTTP 201/409 statuses. A worker can be descheduled between commit and recording, so a rejection can appear before an earlier successful transaction's result. Successes are never sorted to the top.

**OK** means the service returned normally, including a PAYMENT_FAILED terminal state. **REJECT** is an expected domain conflict. **INFO** includes captured inventory and expiry no-ops. Infrastructure errors stop the run and appear as errors, not stock rejections or successful checks.

Use **Pause/Resume**, **Show all**, **Replay**, speed selection, and filters to inspect the recording. These controls never submit new backend writes. **Slow**, **Normal**, and **Fast** schedule rows at 200, 40, and 12 ms respectively, regardless of scenario length. Changing speed reschedules the pending row immediately; it does not restart the recording or resume a paused replay. The selected speed persists across reruns and preset switches. Actual display timing can also depend on browser scheduling. Reduced-motion preferences disable smooth scrolling, but do not bypass the selected playback speed; **Show all** remains available for immediate display.

**Follow log** is checked by default on page load. Unchecking it before a run keeps automatic scrolling off. The choice persists across reruns, replay, and preset switches, and changing it during playback takes effect immediately. Scrolling up with a mouse wheel or navigation keys also turns it off. The log viewport does not use browser scroll anchoring to move the position while following is disabled.

During concurrent reservation and duplicate-request playback, the inventory counters are explicitly labelled **Derived from replayed responses** when calculated from unique successful order IDs and quantities. They are not intermediate database reads. At a recorded snapshot, actual database values replace that calculation. Other scenarios update inventory only at recorded snapshot markers. Final counters use the last recorded database snapshot. For payment/cancel and expiry, the first snapshot is after setup has reserved stock.

Results are historical. The normal scheduler may subsequently expire unpaid reservations. Every run creates a new product and isolated owner; previous data is not reset. Generated rows remain in the local database. A failed or timed-out response does not prove all work was rolled back.

## Execution and safety

The concurrency workload is 100 service calls on 16 workers, not 100 simultaneous HTTP connections. Database concurrency is also bounded by the connection pool. Use `scripts/contention.py` for separate end-to-end HTTP measurements. The expiry fixture moves only its generated order's deadline into the past and invokes the real expiry service; it does not change the application clock or claim to test scheduler timing.

The demo API exists only under the `demo` profile. It keeps CSRF protection, checks the loopback hostname, limits runs to fixed scenarios, and allows one scenario at a time. Compose binds to `127.0.0.1`. Keep this intentionally unauthenticated profile local; the hostname guard is not public-deployment authentication. Normal product, order and admin APIs retain their authentication, role and ownership checks.

Redis caches metadata, not live inventory. Optional Kafka/outbox counts are application-wide observations rather than per-scenario assertions. Enable Kafka with:

```bash
docker compose -f compose.yml -f compose.events.yml --profile events up --build -d
```

## Tests

```bash
npm test
./mvnw test
./mvnw verify
npm run test:browser
```

Java tests check real outcomes and persisted rows against H2 and PostgreSQL, including the activity sequence, order IDs, repeated transitions and expiry no-ops. JavaScript tests cover response validation, stock derivation, duplicate-order handling, paused/resumed playback, speed changes, stale callback cancellation and errors. Browser tests run real scenarios against Compose. Separate deterministic UI tests use fixture responses and a controlled browser clock to check all speed settings, mid-replay speed changes, pause/resume, follow preferences, and removal of unused UI sections. Browser coverage also includes filtering, replay without writes, fresh reruns, errors, mobile layout, reduced-motion behavior and the original workspace.
