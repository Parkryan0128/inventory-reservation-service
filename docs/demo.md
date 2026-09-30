# Inventory Reservation Dashboard

```bash
docker compose up --build -d
```

Open `http://127.0.0.1:8080/`. Choose a scenario and click **Run scenario**. No login, account switching or manual product setup is needed.

The purchase flow explains how buying reserves stock, payment confirms it, and cancellation, failed payment or expiry releases it. The demo profile uses a two-minute hold by default; `RESERVATION_TTL` can override it. Expiry is a separate `EXPIRED` state, not a customer cancellation.

Each scenario explains the situation and how the backend handles it. The scenario panel and Backend activity terminal sit side by side with aligned edges. The terminal retains its playback, speed, filter and follow controls, with inventory counters at the top and compact results in its footer. Expand **Checks** for assertions; failed checks open automatically. The normal account-based workspace remains at `/index.html`.

## Scenarios

| Scenario | Workload | Expected result |
| --- | --- | --- |
| Concurrent reservations | 100 distinct generated customers, 16 server workers, one unit per call, stock 5 | 5 reservations, 95 insufficient-stock rejections, 5 order rows; stock 0 available / 5 reserved / 0 sold |
| Duplicate requests | 16 concurrent identical calls for 3 units; one changed payload using the same key | 16 replies with one order ID; changed quantity rejected; stock 7 / 3 / 0 |
| Payment vs cancel | Reserve one unit, then race confirmation against cancellation with 2 workers | One transition wins and one is rejected; stock matches the winner |
| Order lifecycle | Three orders; repeat confirmation, cancellation and payment failure | Repeated operations do not move stock again; final stock 9 / 0 / 3 |
| Reservation expiry | Hold 2 of 5 units; try early expiry; advance the generated deadline; expire and retry | NO_CHANGE, EXPIRED, NO_CHANGE; both held units returned once |

## Reading the terminal

### Manual mode

The **Manual** tab uses one shared product (`MANUAL-SHARED`) for all visitors. The first opening creates it with five units; later openings look up the same SKU without resetting stock, including after server restarts. Each HTTP session has its own generated owner and latest order. The shared product ID and **Your order** ID remain visible in the sidebar. A session ending loses its order association, not the shared inventory; outstanding reservations still expire normally. Scenario presets continue to create separate products and cannot reset the shared stock.

Use **Add stock**, **Remove stock**, and **Buy** with a whole quantity from 1 to 10. Stock adjustments and reservations take the same database product lock across visitors. Removal affects only available units and cannot consume reserved or sold stock. The total stock baseline remains capped at 1000000 units. Finish your current reserved order with **Confirm payment** or **Cancel**, or let its normal two-minute deadline expire, before buying again. Each visitor can only act on their own order. The backend allows one action per 500 ms per session (`app.manual-action-interval-ms`); excess requests return HTTP 429 with a current snapshot, request-specific result and retry delay, without filling the activity log.

Manual actions return a database snapshot and a separate `result` for that request. The last shared log entry may belong to another visitor, so it is never used to infer your request's success or your current order. The browser updates counters only from server snapshots and never replays or automatically retries a mutation. A transport error leaves the last snapshot marked as stale and disables actions until **Reconnect** checks the current state. Reads poll every four seconds while Manual mode is selected; this is request/response plus polling, not a live event stream.

The application keeps a shared ring buffer of the latest 200 entries. Order transitions are recorded by a synchronous after-commit listener, including scheduler-driven expiry; rolled-back changes and repeated terminal no-ops do not produce transition entries. Stock adjustments and domain rejections are recorded after their transaction ends. Polls and new visitors never append snapshot rows. Entries label the caller as **You**, other callers with a short anonymous visitor label, and expiry as **System**. Shared order summaries omit owner IDs. Entries carry transaction snapshots; sequence numbers describe observation order, not a total database commit order. The latest inventory is read independently and can differ from the last historical entry.

The activity buffer and browser sessions are local to one application process. Restarting the app clears the buffer and changes its stream ID so a connected UI can accept a new sequence after reconnecting. Persistent product, order and outbox rows are retained. There is no automatic stock reset or database cleanup. This demo is intended for a single app instance; a shared database alone does not distribute browser sessions or the in-memory activity feed across replicas.

Manual routes exist only under the local demo profile, retain CSRF protection and accept actions and quantities rather than caller-selected product, order or owner IDs. Every visitor can change the shared available stock, but payment and cancellation always use the current session's own order. GET reads do not create items or perform stock transitions.

### Scenario playback

Each log entry is recorded on the server. Concurrent workers append their results after the real Spring-proxied transactional service returns. Entries include a monotonically increasing sequence, UTC observation timestamp, request ID, actor label, operation, returned status, measured service-call duration, quantity, idempotency key and returned order ID. Hover over a row to inspect the full order ID.

The server returns the completed recording; the browser reveals it progressively. **Replaying recorded results** identifies this presentation phase. It is not a live WebSocket/SSE stream, HTTP access log, SQL trace, lock-wait profiler or reconstructed database commit order. Service calls are not labelled with invented HTTP 201/409 statuses. A worker can be descheduled between commit and recording, so a rejection can appear before an earlier successful transaction's result. Successes are never sorted to the top.

**OK** means the service returned normally, including a PAYMENT_FAILED terminal state. **REJECT** is an expected domain conflict. **INFO** includes captured inventory and expiry no-ops. Infrastructure errors stop the run and appear as errors, not stock rejections or successful checks.

Use **Pause/Resume**, **Show all**, **Replay**, speed selection, and filters to inspect the recording. These controls never submit new backend writes. **Slow**, **Normal**, and **Fast** schedule rows at 200, 40, and 12 ms respectively, regardless of scenario length. Changing speed reschedules the pending row immediately; it does not restart the recording or resume a paused replay. The selected speed persists across reruns and preset switches. Actual display timing can also depend on browser scheduling. Reduced-motion preferences disable smooth scrolling, but do not bypass the selected playback speed; **Show all** remains available for immediate display.

**Follow log** is checked by default on page load. Unchecking it before a run keeps automatic scrolling off. The choice persists across reruns, replay, and preset switches, and changing it during playback takes effect immediately. Scrolling up with a mouse wheel or navigation keys also turns it off. The log viewport does not use browser scroll anchoring to move the position while following is disabled.

During concurrent reservation and duplicate-request playback, the inventory counters are explicitly labelled **Derived from replayed responses** when calculated from unique successful order IDs and quantities. They are not intermediate database reads. At a recorded snapshot, actual database values replace that calculation. Other scenarios update inventory only at recorded snapshot markers. Final counters use the last recorded database snapshot. For payment/cancel and expiry, the first snapshot is after setup has reserved stock.

Results are historical. The normal scheduler may subsequently expire unpaid reservations. Every run creates a new product and isolated owner; previous data is not reset. Generated rows remain in the local database. A failed or timed-out response does not prove all work was rolled back.

## Execution and safety

The concurrency workload is 100 service calls on 16 workers, not 100 simultaneous HTTP connections. Database concurrency is also bounded by the connection pool. Use `scripts/contention.py` for separate end-to-end HTTP measurements. The expiry fixture moves only its generated order's deadline into the past and invokes the real expiry service; it does not change the application clock or claim to test scheduler timing.

The demo API exists only under the `demo` profile. It keeps CSRF protection and checks the loopback hostname. Scenario runs are limited to fixed presets and allow one run at a time. Manual actions are serialized and rate-limited per session; different visitors compete at the database row lock, not a global Java action lock. Compose binds to `127.0.0.1`. Keep this intentionally unauthenticated profile local; neither the hostname guard nor a per-session cooldown is public-deployment authentication or comprehensive abuse protection. Normal product, order and admin APIs retain their authentication, role and ownership checks.

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

Manual coverage includes remove-to-zero/reject/restock/purchase journeys, private orders on shared stock, anonymous shared activity, CSRF, committed expiry events, last-unit contention across visitors, rollback and scenario isolation, the 200-entry limit, per-session rate limiting, quantity bounds and product reuse. Client tests cover independent request outcomes, empty/restarted streams and no automatic write retries. Browser tests cover cross-session changes, mode switches, reload persistence, waiting for server counters and mobile layout.
