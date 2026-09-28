# Validation evidence

## Complete CI acceptance — 2026-09-28

[GitHub Actions run 36461635023](https://github.com/Parkryan0128/inventory-reservation-service/actions/runs/36461635023) **passed** for implementation commit `b68ec4047cd161d43d2a8816e8fadb9cdfc4a85b`. The subsequent documentation commit records those results without changing application code, test code or deployment configuration.

| Gate | Observed result |
| --- | --- |
| Application, concurrency, API security, real HTTP and embedded Kafka delivery/DLT tests | **41 passed**, 0 failures/errors/skips |
| PostgreSQL 16 Testcontainers: schema, inventory, lifecycle, outbox | **26 passed**, 0 failures/errors/skips |
| Redis 7.4 container: metadata/TTL, expiration, stopped-server fallback | **3 passed**, 0 failures/errors/skips |
| Total automated tests | **70 passed** |
| Packaged JAR, Compose configuration and Docker image build | Passed |
| Complete PostgreSQL/Redis/Kafka Compose stack | Started successfully |
| HTTP smoke through the Compose app | Passed: reserve/replay/conflict/payment/repeated callback/stock balance; Kafka audit events delivered and outbox drained |
| HTTP contention through the PostgreSQL-backed Compose app | Passed: 120 requests, 16 clients, 25 units → 25 HTTP 201 + 95 expected HTTP 409; zero remaining stock, 25 reserved |
| Manual browser interaction with the demo | Not performed; assets and the end-to-end API contract were verified over real HTTP |

CI used Java 21 on `ubuntu-latest`, ran `./mvnw -B -ntp verify`, built the non-root app image, and ran both Python scripts against the complete Compose stack. JUnit and coverage reports are attached to the linked workflow run.

Raw CI outputs: [PostgreSQL HTTP smoke](evidence/ci-http-smoke-postgres.json), [PostgreSQL HTTP contention](evidence/ci-http-contention-postgres.json). These describe one short CI run. Its timing numbers are not a capacity benchmark or a production SLO; they include HTTP Basic/BCrypt verification, client overhead, container limits and runner contention.

## Earlier local verification

Before CI, 41 application/HTTP/Kafka tests and 3 native Redis tests passed locally on Java 21, Maven 3.9.11 and Linux. The local application database was H2 and native Redis was version 7.0.15. The portable equivalent of that Maven command was:

```bash
TEST_REDIS_BINARY=/path/to/redis-server ./mvnw -Dit.test=RedisIntegrationIT verify
```

This explicitly selected Redis integration tests and did not prove PostgreSQL locking semantics. The full CI run above subsequently closed that gap. Default `./mvnw verify` requires PostgreSQL and Redis containers and never silently skips unavailable Docker infrastructure.

The earlier local Python smoke/contention checks ran with the `test,demo` profiles (H2), scheduling enabled, port 18080, `-Xmx512m` and `-XX:ActiveProcessorCount=4`. Kafka was disabled for that HTTP-only run and tested separately with the embedded broker. Raw historical outputs: [H2 HTTP smoke](evidence/http-smoke-h2.json), [H2 HTTP contention](evidence/http-contention-h2.json). JavaScript syntax, Python compilation, YAML parsing and whitespace checks also passed locally.

## Bugs caught while building

- The Redis harness waited on a background-thread lambda during static initialization, causing an initialization lock wait. Same-thread readiness polling removed the harness deadlock.
- The library's default DLT suffix differed from the declared `orders.v1.DLT` topic. A real poison-event test exposed the mismatch; an explicit destination resolver fixed it.
- An error assertion expected prose containing “stock”, but the error text used “inventory”. Tests now check the stable `INSUFFICIENT_STOCK` code.

## Reproduce on a Docker-capable host

```bash
./mvnw -B -ntp verify
docker compose -f compose.yml -f compose.events.yml --profile events config --quiet
docker compose -f compose.yml -f compose.events.yml --profile events up -d --build
python3 scripts/smoke.py --wait 150 --require-events
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
```

The workflow retains JUnit/coverage artifacts and prints service logs on failure.
