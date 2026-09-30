import test from "node:test";
import assert from "node:assert/strict";
import { DemoClient, scenarios, validateRecording, summarize } from "../../main/resources/static/demo-client.js";

const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
function result(scenario, passed = true) {
  const quantity = { contention: 100, idempotency: 17, race: 2, lifecycle: 0, expiry: 0 }[scenario];
  const attempts = Array.from({ length: quantity }, (_, i) => ({
    code: scenario === "contention" ? i < 5 ? "RESERVED" : "INSUFFICIENT_STOCK" : scenario === "idempotency" ? i < 16 ? "RESERVED" : "IDEMPOTENCY_CONFLICT" : i === 0 ? "CONFIRMED" : "INVALID_TRANSITION",
    orderId: scenario === "contention" ? i < 5 ? `order-${i}` : null : i === quantity - 1 ? null : "order-1", durationMs: 5,
  }));
  const stocks = { contention: [[5, 0, 0], [0, 5, 0]], idempotency: [[10, 0, 0], [7, 3, 0]], race: [[0, 1, 0], [0, 0, 1]], lifecycle: [[12, 0, 0], [9, 0, 3]], expiry: [[3, 2, 0], [5, 0, 0]] }[scenario];
  return { scenario, passed, attempts, snapshots: stocks.map(([available, reserved, sold], i) => ({ label: `Step ${i}`, inventory: { id: "product-1", available, reserved, sold, initialStock: available + reserved + sold } })), checks: { expected: passed }, outcomes: attempts.reduce((counts, attempt) => { counts[attempt.code] = (counts[attempt.code] || 0) + 1; return counts; }, {}), durationMs: 55, persistedOrders: { contention: 5, idempotency: 1, race: 1, lifecycle: 3, expiry: 1 }[scenario], completedAt: "2026-09-30T00:00:00Z", note: "Recorded test fixture" };
}
function fetcher(log, overrides = {}) {
  return async (path, options) => {
    log.push({ path, options });
    if (path === "/api/csrf") return response({ headerName: "X-CSRF-TOKEN", token: "fresh-csrf" });
    const name = path.split("/").at(-1);
    return overrides[name] || response(result(name));
  };
}

test("selected scenarios run sequentially with CSRF and no embedded credentials", async () => {
  const log = [];
  const client = new DemoClient(fetcher(log));
  const starts = [];
  const finishes = [];
  const results = await client.run(scenarios, (name) => finishes.push(name), (name) => starts.push(name));
  assert.deepEqual(starts, scenarios);
  assert.deepEqual(finishes, scenarios);
  assert.equal(results.length, 5);
  const writes = log.filter(({ options }) => options.method === "POST");
  assert.equal(writes.length, 5);
  for (const { options } of writes) {
    assert.deepEqual(options.headers, { "X-CSRF-TOKEN": "fresh-csrf" });
    assert.equal(options.credentials, "same-origin");
  }
  assert.equal(client.running, false);
});

test("a failed assertion remains a failure and later selected scenarios still run", async () => {
  const client = new DemoClient(fetcher([], { contention: response(result("contention", false)) }));
  assert.deepEqual((await client.run(["contention", "race"])).map((item) => item.passed), [false, true]);
});

test("transport errors stop execution without retrying writes or fabricating outcomes", async () => {
  const log = [];
  const client = new DemoClient(fetcher(log, { contention: response({ detail: "Database unavailable" }, 500) }));
  const finished = [];
  await assert.rejects(client.run(scenarios, (name, value, error) => finished.push({ name, value, error })), /Database unavailable/);
  assert.equal(log.filter(({ options }) => options.method === "POST").length, 1);
  assert.equal(finished[0].value, null);
  assert.ok(finished[0].error);
  assert.equal(client.running, false);
});

test("busy and forbidden responses are explicit errors", async () => {
  for (const status of [409, 403]) {
    const client = new DemoClient(fetcher([], { race: response({}, status) }));
    await assert.rejects(client.run(["race"]), status === 409 ? /Another demo/ : /blocked/);
    assert.equal(client.running, false);
  }
});

test("network failure releases the client guard", async () => {
  const client = new DemoClient(async () => { throw new Error("offline"); });
  await assert.rejects(client.run(["race"]), /offline/);
  assert.equal(client.running, false);
});

test("malformed and mismatched results are rejected", async () => {
  for (const body of [{}, result("expiry"), { ...result("race"), passed: "true" }]) {
    const client = new DemoClient(fetcher([], { race: response(body) }));
    await assert.rejects(client.run(["race"]), /invalid demo result/);
  }
});

test("invalid scenarios are rejected before any request", async () => {
  const log = [];
  const client = new DemoClient(fetcher(log));
  await assert.rejects(client.run(["reset-database"]), /known demo/);
  await assert.rejects(client.run([]), /known demo/);
  assert.equal(log.length, 0);
});

test("rapid double clicks cannot launch a second run", async () => {
  let release;
  const blocked = new Promise((resolve) => { release = resolve; });
  const client = new DemoClient(async (path) => {
    if (path === "/api/csrf") { await blocked; return response({ headerName: "X-CSRF-TOKEN", token: "token" }); }
    return response(result("race"));
  });
  const first = client.run(["race"]);
  await assert.rejects(client.run(["race"]), /already running/);
  release();
  await first;
  assert.equal(client.running, false);
});

test("non-JSON server errors are readable", async () => {
  const client = new DemoClient(async () => ({ ok: false, status: 502, json: async () => { throw new Error("HTML response"); } }));
  await assert.rejects(client.status(), /unreadable response \(HTTP 502\)/);
});

test("summaries use returned counts and keep reserved units distinct from sales", () => {
  const view = summarize(result("contention"));
  assert.equal(view.title, "5 reserved. 95 turned away.");
  assert.match(view.note, /None have been sold/);
  assert.deepEqual(view.metrics, [["Orders created", 5], ["No stock left", 95], ["Oversold units", 0]]);
  assert.equal(summarize(result("idempotency")).title, "16 replies. 1 order.");
  assert.equal(summarize(result("expiry")).metrics[0][1], 2);
});

test("either race winner is derived from the actual response, not a fixed animation", () => {
  const r = result("race");
  assert.match(summarize(r).title, /^Payment won/);
  r.attempts = [{ code: "INVALID_TRANSITION", orderId: null, durationMs: 4 }, { code: "CANCELLED", orderId: "order-1", durationMs: 2 }];
  r.outcomes = { CANCELLED: 1, INVALID_TRANSITION: 1 };
  r.snapshots[1].inventory = { ...r.snapshots[1].inventory, available: 1, sold: 0 };
  assert.match(summarize(r).title, /^Cancellation won/);
});

test("contradictory pass flags and unbalanced stock cannot produce a success headline", () => {
  for (const change of [r => { r.passed = false; }, r => { r.checks.extra = false; }, r => { r.snapshots[1].inventory.reserved = 6; }, r => { r.snapshots[1].inventory.available = -1; }]) {
    const r = result("contention");
    change(r);
    assert.equal(summarize(r).ok, false);
    assert.equal(summarize(r).title, "The run needs attention.");
  }
});

test("recordings require real records, consistent counts and valid stock fields", () => {
  for (const change of [r => { r.attempts.pop(); }, r => { r.outcomes.RESERVED++; }, r => { r.snapshots = []; }, r => { r.checks = {}; }, r => { r.snapshots[1].inventory.id = "other"; }, r => { r.durationMs = NaN; }, r => { r.completedAt = "invalid"; }, r => { r.attempts[0].durationMs = -1; }, r => { r.snapshots[0].inventory.available = "5"; }, r => { r.attempts[0].orderId = undefined; }]) {
    const r = result("contention");
    change(r);
    assert.throws(() => validateRecording(r), /invalid demo result/);
  }
});

test("missing CSRF data prevents the write", async () => {
  let requests = 0;
  const client = new DemoClient(async () => { requests++; return response({}); });
  await assert.rejects(client.run(["race"]), /CSRF/);
  assert.equal(requests, 1);
  assert.equal(client.running, false);
});
