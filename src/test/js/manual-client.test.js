import test from "node:test";
import assert from "node:assert/strict";
import { ManualClient, validateManualState } from "../../main/resources/static/demo-client.js";

const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const csrf = () => response({ headerName: "X-CSRF-TOKEN", token: "test-token" });
function state() {
  const inventory = { id: "product-1", available: 5, reserved: 0, sold: 0, initialStock: 5 };
  return {
    inventory, order: null, observedAt: "2026-01-01T12:00:00Z",
    activity: [{ sequence: 1, recordedAt: "2026-01-01T12:00:00Z", operation: "refresh", code: "SNAPSHOT",
      level: "info", message: "Database snapshot", quantity: 0, durationMs: 0, inventory: { ...inventory }, order: null }],
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
  for (const q of [-1, 0, 1.5, 10001, NaN]) await assert.rejects(client.act("BUY", q), /whole quantity/);
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
  await assert.rejects(client.state(), /session changed/);
});

test("infrastructure errors and malformed failure payloads are not successful activity", async () => {
  const client = new ManualClient(async path => path === "/api/csrf" ? csrf() : response({ detail: "Database unavailable" }, 503));
  await assert.rejects(client.act("BUY", 1), /Database unavailable/);
  const invalid = new ManualClient(async path => path === "/api/csrf" ? csrf() : response(state(), 409));
  await assert.rejects(invalid.act("BUY", 1), /invalid manual result/);
});
