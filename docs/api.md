# API contract

All amounts are integer cents; timestamps are UTC ISO-8601; identifiers are UUIDs. JSON errors use `application/problem+json` with a machine-readable `code` where provided by the application/security handlers.

All `/api` endpoints except `/api/csrf` require HTTP Basic authentication. Every POST also requires the CSRF token and the session cookie returned by `/api/csrf`. Fetch a new token after restarting the server or losing the cookie. HTTPS is mandatory outside local development.

| Method / path | Access | Behavior |
| --- | --- | --- |
| GET `/api/csrf` | Public | Returns `headerName`, `parameterName`, `token`; sets CSRF session cookie |
| GET `/api/me` | Customer/admin | Current username and roles |
| POST `/api/products` | Admin | Create SKU, name, positive priceCents, USD/CAD, stock 0..1,000,000 |
| GET `/api/products?page=0` | Customer/admin | 50 products per page, current DB counters |
| GET `/api/products/{id}` | Customer/admin | Current DB product and counters |
| GET `/api/catalog/{id}` | Customer/admin | Metadata only, optional Redis cache |
| POST `/api/orders` | Customer/admin | Reserve `{productId, quantity}`; required `Idempotency-Key` header |
| GET `/api/orders?page=0` | Customer/admin | Own latest orders, 50 per page |
| GET `/api/orders/{id}` | Owner | Get own order |
| POST `/api/orders/{id}/cancel` | Owner | Cancel reserved order; no JSON body needed |
| POST `/api/admin/payments/{id}` | Admin | Simulated callback `{"success":true}` or `false` |
| GET `/api/admin/orders` | Admin | Latest 50 orders across users |
| GET `/api/admin/status` | Admin | Total orders, pending events, audit receipts, relay enabled flag |
| GET `/actuator/health` | Public | Service health without internal details |
| GET `/actuator/prometheus` | Admin | JVM/HTTP/cache metrics |

Example reservation body:

```json
{"productId":"123e4567-e89b-12d3-a456-426614174000","quantity":2}
```

Use an existing product ID. Quantity is 1..10,000. Keys contain 1..80 characters from `[A-Za-z0-9._:-]`, scoped to the authenticated username. Both initial reservations and matching retries return `201`, a `Location` header and the same order ID; a replay returns the current state. A successful late-payment response can have status `EXPIRED`: clients must inspect the order status, not just HTTP 200.

Common errors: 400 `INVALID_REQUEST`; 401 `UNAUTHENTICATED`; 403 `FORBIDDEN` (including missing/invalid CSRF); 404 `NOT_FOUND`; 409 `INSUFFICIENT_STOCK`, `IDEMPOTENCY_CONFLICT`, `INVALID_TRANSITION`, or `DATA_CONFLICT`; 503 `RETRY_LATER` for translated lock contention. Error responses never include raw SQL or stack traces.

For complete executable examples including cookie and CSRF handling, use `scripts/api_client.py` and `scripts/smoke.py`. The simulator and admin listing are demo tools, not a public payment-provider webhook.
