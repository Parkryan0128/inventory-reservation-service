export function recording(scenario = "contention") {
  const stocks = {
    contention: [[5, 0, 0], [0, 5, 0]],
    idempotency: [[10, 0, 0], [7, 3, 0], [7, 3, 0]],
    race: [[0, 1, 0], [0, 0, 1]],
    lifecycle: [[12, 0, 0], [9, 3, 0], [9, 0, 3], [7, 2, 3], [9, 0, 3], [7, 2, 3], [9, 0, 3]],
    expiry: [[3, 2, 0], [5, 0, 0]],
  }[scenario];
  const r = { scenario, passed: true, durationMs: 180, completedAt: "2026-01-01T12:00:01Z", note: "Test fixture", checks: { "Stock is balanced": true },
    persistedOrders: { contention: 5, idempotency: 1, race: 1, lifecycle: 3, expiry: 1 }[scenario],
    snapshots: stocks.map(([available, reserved, sold], i) => ({ label: `Step ${i + 1}`, inventory: { id: "product-1", initialStock: stocks[0].reduce((a, b) => a + b), available, reserved, sold } })),
    attempts: [], outcomes: {}, activity: [] };
  const entry = (data) => r.activity.push({ sequence: r.activity.length + 1, recordedAt: "2026-01-01T12:00:00.123Z", requestId: "", actor: "database", operation: "inventory", code: "SNAPSHOT", orderId: null, quantity: 0, key: "", durationMs: 0, snapshotIndex: null, ...data });
  const snap = i => entry({ snapshotIndex: i });
  const call = (id, operation, code, orderId, quantity = 1, key = "", actor = "customer-001") => entry({ requestId: id, actor, operation, code, orderId, quantity, key, durationMs: 5 });
  const request = (i, code, order, qty = 1, key = "", op = "reserve") => {
    r.attempts.push({ code, orderId: order, durationMs: 5 });
    call(`req-${String(i + 1).padStart(3, "0")}`, op, code, order, qty, key, scenario === "contention" ? `customer-${i + 1}` : "customer-001");
  };
  if (scenario === "contention") {
    snap(0);
    for (let i = 0; i < 100; i++) request(i, i < 5 ? "RESERVED" : "INSUFFICIENT_STOCK", i < 5 ? `order-${i}` : null, 1, `key-${i}`);
    snap(1);
  } else if (scenario === "idempotency") {
    snap(0);
    for (let i = 0; i < 16; i++) request(i, "RESERVED", "order-1", 3, "same-key");
    snap(1);
    request(16, "IDEMPOTENCY_CONFLICT", null, 4, "same-key");
    snap(2);
  } else if (scenario === "race") {
    call("setup", "reserve", "RESERVED", "order-1");
    snap(0);
    request(0, "CONFIRMED", "order-1", 1, "", "payment-success");
    request(1, "INVALID_TRANSITION", null, 1, "", "cancel");
    snap(1);
  } else if (scenario === "lifecycle") {
    snap(0);
    ["CONFIRMED", "CANCELLED", "PAYMENT_FAILED"].forEach((code, i) => {
      call(`reserve-${i}`, "reserve", "RESERVED", `order-${i}`, i ? 2 : 3);
      snap(i * 2 + 1);
      for (let j = 0; j < 2; j++) call(`finish-${i}-${j}`, ["payment-success", "cancel", "payment-failure"][i], code, `order-${i}`, i ? 2 : 3);
      snap(i * 2 + 2);
    });
  } else {
    call("setup", "reserve", "RESERVED", "order-1", 2);
    snap(0);
    call("expiry-1", "expire", "NO_CHANGE", "order-1", 2);
    call("fixture", "advance-deadline", "DEADLINE_ADVANCED", "order-1", 0);
    call("expiry-2", "expire", "EXPIRED", "order-1", 2);
    call("expiry-3", "expire", "NO_CHANGE", "order-1", 2);
    snap(1);
  }
  r.outcomes = r.attempts.reduce((o, a) => { o[a.code] = (o[a.code] || 0) + 1; return o; }, {});
  return r;
}
