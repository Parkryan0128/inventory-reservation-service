# Validation evidence

Last local verification: **2026-09-28**. Java 21, Maven 3.9.11, Linux; H2 application database, real embedded KRaft Kafka broker, and native Redis 7.0.15.

| Gate | Observed result |
| --- | --- |
| Application, concurrency, API security, real HTTP, Kafka delivery/DLT tests | **41 passed**, 0 failures/errors/skips |
| Actual Redis storage/TTL, expiration, stopped-server fallback | **3 passed**, 0 failures/errors/skips |
| Packaged Spring Boot JAR | Built successfully |
| Running-server Python HTTP smoke | Passed: reserve/replay/conflict/payment/repeated callback/stock balance |
| Running-server HTTP contention | Passed: 120 requests, 16 clients, 25 units → 25 HTTP 201 + 95 expected HTTP 409; zero remaining stock, 25 reserved |
| JavaScript syntax / Python compilation / YAML parsing / whitespace check | Passed |
| PostgreSQL Testcontainers acceptance (26 inherited cases) | **Not executed in this environment: no Docker daemon** |
| Docker image build and complete Compose runtime | **Not executed in this environment**; configured as required CI gates |
| Manual browser interaction with the demo | Not performed; assets and the end-to-end API contract were verified over real HTTP |

The portable equivalent of the successful local Maven command is:

```bash
TEST_REDIS_BINARY=/path/to/redis-server ./mvnw -Dit.test=RedisIntegrationIT verify
```

The test selection is explicit: this run does **not** exercise PostgreSQL. The default `./mvnw verify` and GitHub Actions require PostgreSQL and Redis containers and never silently skip unavailable Docker infrastructure.

For the Python smoke/contention checks, the service ran with the `test,demo` profiles (H2), scheduling enabled, port 18080, `-Xmx512m` and `-XX:ActiveProcessorCount=4`. Kafka was disabled for this HTTP-only run; Kafka was exercised separately by the embedded-broker tests.

Raw outputs: [HTTP smoke](evidence/http-smoke-h2.json), [HTTP contention](evidence/http-contention-h2.json). The included timing numbers describe this single short local run and are not representative capacity claims. Do not present them as PostgreSQL, production or cloud-VM performance.

## Bugs caught while building

- The Redis harness waited on a background-thread lambda during static initialization, causing an initialization lock wait. Same-thread readiness polling removed the harness deadlock.
- The library's default DLT suffix differed from the declared `orders.v1.DLT` topic. A real poison-event test exposed the mismatch; an explicit destination resolver fixed it.
- An error assertion expected prose containing “stock”, but the error text used “inventory”. Tests now check the stable `INSUFFICIENT_STOCK` code.

## Acceptance on a Docker-capable host

```bash
./mvnw -B -ntp verify
docker compose -f compose.yml -f compose.events.yml --profile events config --quiet
docker compose -f compose.yml -f compose.events.yml --profile events up -d --build
python3 scripts/smoke.py --wait 150 --require-events
python3 scripts/contention.py --requests 120 --stock 25 --workers 16
```

These are the remaining runtime gates, not claimed successful results. The workflow records JUnit/coverage artifacts and service logs on failure.
