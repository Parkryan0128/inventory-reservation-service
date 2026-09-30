import test from "node:test";
import assert from "node:assert/strict";
import { ManualClient, validateManualState } from "../../main/resources/static/demo-client.js";

const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const csrf = () => response({ headerName: "X-CSRF-TOKEN", token: "test-token" });
function state() {
  const inventory = { id: "product-1", available: 5, reserved: 0, sold: 0, initialStock: 5 };
  return {
    inventory, order: null, observedAt: "2026-01-01T12:00:00Z",
    streamId: "stream-1", visitorId: "visitor-1", retryAfterMs: 0,
    result: { code: "STOCK_ADDED", level: "ok", message: "Request committed", durationMs: 2 },
    activity: [{ sequence: 1, recordedAt: "2026-01-01T12:00:00Z", operation: "refresh", code: "SNAPSHOT",
      actor: "You", level: "info", message: "Database snapshot", quantity: 0, inventory: { ...inventory }, order: null }],
  };
}

test("manual writes use CSRF and the session; reads never create or change an item", async () => {
  const calls = [];
  const client = new ManualClient(async (path, options) => {
    calls.push({ path, options });
    return path === "/api/csrf" ? csrf() : response(state());
  });
  await client.open();
  await client.state();
  await client.act("REMOVE_STOCK", 2);
  const writes = calls.filter(c => c.options.method === "POST");
  assert.deepEqual(writes.map(c => c.path), ["/api/demo/manual", "/api/demo/manual/actions"]);
  for (const { options } of writes) {
    assert.equal(options.credentials, "same-origin");
    assert.equal(options.headers["X-CSRF-TOKEN"], "test-token");
  }
  assert.deepEqual(JSON.parse(writes[1].options.body), { action: "REMOVE_STOCK", quantity: 2 });
  assert.equal(calls.find(c => c.path === "/api/demo/manual" && !c.options.method).options.cache, "no-store");
});

test("stock rejection retains the server snapshot and domain reason", async () => {
  const rejected = state();
  Object.assign(rejected.activity[0], { code: "INSUFFICIENT_STOCK", level: "rejected", operation: "buy", message: "Not enough inventory" });
  Object.assign(rejected.result, { code: "INSUFFICIENT_STOCK", level: "rejected", message: "Not enough inventory" });
  const client = new ManualClient(async path => path === "/api/csrf" ? csrf() : response(rejected, 409));
  const result = await client.act("BUY", 6);
  assert.equal(result.inventory.available, 5);
  assert.equal(result.activity.at(-1).code, "INSUFFICIENT_STOCK");
});

test("uncertain manual writes are never retried and release the in-flight guard", async () => {
  let writes = 0;
  const client = new ManualClient(async path => {
    if (path === "/api/csrf") return csrf();
    writes++;
    throw new Error("connection lost after write");
  });
  await assert.rejects(client.act("ADD_STOCK", 1), /connection lost/);
  assert.equal(writes, 1);
  assert.equal(client.running, false);
});

test("rapid clicks cannot send duplicate stock mutations", async () => {
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  let writes = 0;
  const client = new ManualClient(async path => {
    if (path === "/api/csrf") { await wait; return csrf(); }
    writes++;
    return response(state());
  });
  const first = client.act("ADD_STOCK", 1);
  await assert.rejects(client.act("ADD_STOCK", 1), /already running/);
  release();
  await first;
  assert.equal(writes, 1);
});

test("invalid quantities, unknown actions and missing CSRF cannot write", async () => {
  let calls = 0;
  const client = new ManualClient(async () => { calls++; return response({}); });
  for (const q of [-1, 0, 1.5, 11, NaN]) await assert.rejects(client.act("BUY", q), /whole quantity/);
  await assert.rejects(client.act("DELETE_ALL", 1), /whole quantity/);
  assert.equal(calls, 0);
  await assert.rejects(client.open(), /CSRF/);
  assert.equal(calls, 1);
});

test("corrupt snapshots, unknown order states and different products are rejected", async () => {
  for (const mutate of [s => s.inventory.available = -1, s => s.activity = [null], s => s.activity[0].sequence = 0,
    s => s.activity[0].inventory.id = "other", s => s.inventory.reserved = 1,
    s => s.order = { id: "order", productId: "product-1", quantity: 1, status: "MADE_UP", expiresAt: s.observedAt },
    s => s.activity[0].recordedAt = "not a timestamp"]) {
    const s = state(); mutate(s);
    assert.throws(() => validateManualState(s), /invalid manual result/);
  }
  let s = state();
  const client = new ManualClient(async () => response(s));
  await client.state();
  s = state(); s.inventory.id = "other"; s.activity[0].inventory.id = "other";
  await assert.rejects(client.state(), /shared demo item changed/);
});

test("infrastructure errors and malformed failure payloads are not successful activity", async () => {
  const client = new ManualClient(async path => path === "/api/csrf" ? csrf() : response({ detail: "Database unavailable" }, 503));
  await assert.rejects(client.act("BUY", 1), /Database unavailable/);
  const invalid = new ManualClient(async path => path === "/api/csrf" ? csrf() : response(state(), 409));
  await assert.rejects(invalid.act("BUY", 1), /invalid manual result/);
});

test("another visitor's last event cannot replace this request's result or own order", async () => {
  const s = state();
  s.activity[0].actor = "Visitor abcd1234";
  s.activity[0].order = { id: "other-order", productId: "product-1", quantity: 1, status: "RESERVED", expiresAt: s.observedAt };
  s.activity[0].inventory = { id: "product-1", available: 4, reserved: 1, sold: 0, initialStock: 5 };
  s.activity[0].code = "RESERVED";
  s.activity[0].level = "ok";
  s.result = { code: "INSUFFICIENT_STOCK", level: "rejected", message: "Not enough inventory", durationMs: 3 };
  const client = new ManualClient(async path => path === "/api/csrf" ? csrf() : response(s, 409));
  const result = await client.act("BUY", 6);
  assert.equal(result.order, null);
  assert.equal(result.result.code, "INSUFFICIENT_STOCK");
  assert.equal(result.activity.at(-1).level, "ok");
});

test("empty logs and restarted streams preserve the shared product", async () => {
  let s = state();
  s.activity = [];
  s.result = null;
  const client = new ManualClient(async () => response(s));
  assert.equal((await client.state()).activity.length, 0);
  s = state(); s.activity[0].sequence = 501;
  assert.equal((await client.state()).activity[0].sequence, 501);
  s = state(); s.streamId = "restarted";
  assert.equal((await client.state()).activity[0].sequence, 1);
});

test("rate limiting keeps the live state available without retrying the write", async () => {
  const s = state();
  s.result = { code: "RATE_LIMITED", level: "rejected", message: "Wait briefly", durationMs: 0 };
  s.retryAfterMs = 450;
  let writes = 0;
  const client = new ManualClient(async path => {
    if (path === "/api/csrf") return csrf();
    writes++;
    return response(s, 429);
  });
  assert.equal((await client.act("BUY", 1)).retryAfterMs, 450);
  assert.equal(writes, 1);
});

test("shared stream metadata, event ordering and action results are validated", async () => {
  for (const mutate of [s => s.streamId = "", s => s.visitorId = null, s => s.retryAfterMs = -1,
    s => s.retryAfterMs = 60001, s => s.activity[0].actor = null,
    s => s.activity.push({ ...s.activity[0] }), s => s.result = undefined,
    s => s.result.durationMs = -1, s => s.activity = Array(201).fill(s.activity[0])]) {
    const s = state(); mutate(s);
    assert.throws(() => validateManualState(s), /invalid manual result/);
  }
  const s = state(); s.result = null;
  const client = new ManualClient(async path => path === "/api/csrf" ? csrf() : response(s));
  await assert.rejects(client.act("BUY", 1), /invalid manual result/);
});
