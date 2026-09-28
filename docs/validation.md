# Validation evidence

## Refactor acceptance — 2026-09-28

[GitHub Actions run 36465926585](https://github.com/Parkryan0128/inventory-reservation-service/actions/runs/36465926585) **passed** for commit `f3bc790c29052d4d8eb85b2b5f2a259e136a30a7`. The following documentation commit records the results without changing application code, tests or deployment configuration.

| Check | Result |
| --- | --- |
| Application, concurrency, API security, real HTTP and embedded Kafka tests | 50 passed |
| PostgreSQL 16: schema, inventory, lifecycle and outbox | 26 passed |
| Redis 7.4: metadata/TTL, expiration and stopped-server fallback | 3 passed |
| JavaScript: session cancellation, account isolation, errors and native fetch binding | 6 passed |
| Chromium: complete order journey and account switching during an outstanding request | 2 passed |
| **Total** | **87 passed; no failures, errors or skipped tests** |
| Java formatting, packaged JAR, Compose configuration and Docker build | Passed |
| Complete PostgreSQL/Redis/Kafka Compose stack | Passed |
| HTTP smoke | Reserve, replay, conflicting retry, payment and repeated callback passed; Kafka audit events arrived and the outbox drained |
| HTTP contention | 120 requests from 16 clients for 25 units: **25 HTTP 201 + 95 expected HTTP 409**, with 0 available and 25 reserved |

The browser journey creates a product as admin, reserves and replays as Alice, checks Bob's empty order list, and confirms payment as admin. It verifies the resulting stock counts and checks for horizontal overflow at a 390-pixel viewport. The second browser test delays Alice's order response while switching to Bob and verifies that old account data does not reappear.

JUnit, browser and coverage reports are attached to the workflow run. Raw HTTP outputs: [smoke](evidence/refactor-http-smoke-postgres.json), [contention](evidence/refactor-http-contention-postgres.json). Timing values describe this short CI run, including authentication and shared-runner overhead; they are not capacity benchmarks.

## Regressions addressed

- Decimal and quoted numeric inputs were silently coerced into quantities or prices. The API now rejects them, along with null primitive values, using `400 INVALID_REQUEST`. Tests verify that rejected reservations leave stock unchanged.
- Cache entries with missing product metadata could be returned as valid hits. Invalid entries now fall back to the database. Redis/data-access and JSON failures are recoverable; unrelated programming errors are no longer swallowed as cache outages.
- The demo shared credentials, pending requests and replay state across account changes. Each connection now owns those values, cancels its requests on disconnect, and rejects late responses. A completed write also triggers a fresh read after any older poll.
- The first browser run during this refactor caught a missing native `fetch` receiver binding in the new session client. A regression test reproduces that error; the binding fix and the complete browser journey passed in the run linked above.

Java sources now use consistent formatting, with a Spotless check in the Maven build. Main-source wildcard imports, compressed declarations and boolean-controlled locking were cleaned up. The demo copy and implementation notes were shortened.

## Reproduce

Requires Java 21, Node.js 22 or newer, and a Docker-capable host.

```bash
npm ci
npm test
./mvnw -B -ntp verify
docker compose -f compose.yml -f compose.events.yml --profile events config --quiet
docker compose -f compose.yml -f compose.events.yml --profile events up -d --build
python3 scripts/smoke.py --wait 150 --require-events
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
npx playwright install --with-deps chromium
npm run test:browser
```

The workflow retains reports and prints service logs on failure. Browser traces and screenshots are retained for failed browser tests.

## Local and earlier checks

Local verification used Java 21, Maven 3.9.11, H2 and native Redis 7.0.15: 50 application tests and 3 Redis tests passed. All 6 JavaScript tests also passed. A running local HTTP server passed the smoke and 120-request contention checks. Local browser execution was blocked by the execution environment; the browser tests ran successfully in GitHub CI against the PostgreSQL-backed Compose app.

For a machine with native Redis but no Docker:

```bash
TEST_REDIS_BINARY=/path/to/redis-server ./mvnw -Dit.test=RedisIntegrationIT verify
```

This selection omits PostgreSQL; it does not prove PostgreSQL locking behavior. Default `./mvnw verify` requires PostgreSQL and Redis containers and fails if Docker is unavailable.

The initial implementation passed [run 36461635023](https://github.com/Parkryan0128/inventory-reservation-service/actions/runs/36461635023) with 70 Java tests and the Compose smoke/contention checks. Historical outputs remain available: [initial PostgreSQL smoke](evidence/ci-http-smoke-postgres.json), [initial PostgreSQL contention](evidence/ci-http-contention-postgres.json), [initial H2 smoke](evidence/http-smoke-h2.json), [initial H2 contention](evidence/http-contention-h2.json).
