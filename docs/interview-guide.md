# Interview notes

Explain the guarantees before the frameworks. Walk through these scenarios with the code and tests open:

1. **120 clients want 25 units.** Show `OrderService.reserve`, product row locking, the database CHECK and `InventoryTest`. Say exactly which database executed the evidence you show.
2. **The client loses its response and retries.** Show the owner/key constraint and the cross-product key race handled outside the failed transaction. Explain why a JVM-local mutex is insufficient with multiple instances.
3. **Payment and expiration race.** Show lock ordering, rechecking time after waiting, and the single transition which changes inventory.
4. **Kafka acknowledges, then the application crashes.** Explain the duplicate delivery window and the consumer receipt constraint. Do not claim exactly-once delivery.
5. **Redis disappears.** Show the real Redis outage test and explain why availability/price checks do not trust the cache.
6. **An event arrives out of order.** The audit sink is commutative; a fulfillment/current-state projection would need revision guards and independent side-effect idempotency.
7. **What did the tests catch?** The real Kafka DLT test caught a mismatch between the library's default dead-letter topic suffix and the application's declared `.DLT` topic. An explicit resolver makes the contract independent of that default.
8. **What would you improve next?** Measure the hot-product path, reduce authentication overhead with a proper identity provider, bound work queues, design outbox retention/replay, and add broker/database redundancy. Choose from measured bottlenecks, not additional framework names.

Possible resume bullets, after reviewing the implementation and reproducing the relevant gates:

- Built a Java/Spring Boot inventory reservation service with transactional stock updates, caller-scoped idempotency, and race-safe payment/cancellation/expiry transitions.
- Implemented a transactional outbox and Kafka audit consumer with retry/backoff, event deduplication and dead-letter handling; verified broker delivery and Redis outage fallback with integration tests.

Only add numerical latency/throughput claims after recording the hardware, database, dataset, test command and raw results.
