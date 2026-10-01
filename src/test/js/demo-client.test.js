import test from "node:test";
import assert from "node:assert/strict";
import { DemoClient, Replay, scenarios, validateRecording, validateActivity, recordingPassed, inventoryAt, entryLevel } from "../../main/resources/static/demo-client.js";
import { recording } from "./fixtures.js";
const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
function fetcher(log, overrides = {}) {
  return async (path, options) => {
    log.push({ path, options });
    if (path === "/api/csrf") return response({ headerName: "X-CSRF-TOKEN", token: "test-token" });
    const scenario = path.split("/").at(-1);
    return overrides[scenario] || response(recording(scenario));
  };
}

test("every scenario has consistent attempts, activity, snapshots and summaries", () => {
  for (const name of scenarios) {
    assert.equal(validateActivity(recording(name)).scenario, name);
    assert.equal(recordingPassed(recording(name)), true);
  }
});
test("selected scenarios execute once with CSRF and no embedded credentials", async () => {
  const log = [], starts = [], finishes = [];
  const client = new DemoClient(fetcher(log));
  const results = await client.run(scenarios, n => finishes.push(n), n => starts.push(n));
  assert.deepEqual(starts, scenarios);
  assert.deepEqual(finishes, scenarios);
  assert.equal(results.length, 5);
  const writes = log.filter(e => e.options.method === "POST");
  assert.equal(writes.length, 5);
  for (const { options } of writes) {
    assert.deepEqual(options.headers, { "X-CSRF-TOKEN": "test-token" });
    assert.equal(options.credentials, "same-origin");
  }
  assert.equal(client.running, false);
});
test("server assertion failure stays failed without inventing success", async () => {
  const r = recording(); r.passed = false;
  const result = await new DemoClient(fetcher([], { contention: response(r) })).run(["contention"]);
  assert.equal(recordingPassed(result[0]), false);
});
test("transport errors stop execution without retrying writes", async () => {
  const log = [], finished = [];
  const client = new DemoClient(fetcher(log, { contention: response({ detail: "Database unavailable" }, 500) }));
  await assert.rejects(client.run(scenarios, (name, value, error) => finished.push({ name, value, error })), /Database unavailable/);
  assert.equal(log.filter(e => e.options.method === "POST").length, 1);
  assert.equal(finished[0].value, null);
  assert.ok(finished[0].error);
  assert.equal(client.running, false);
});
test("busy, forbidden and unreadable responses are explicit errors", async () => {
  for (const status of [409, 403]) {
    const client = new DemoClient(fetcher([], { race: response({}, status) }));
    await assert.rejects(client.run(["race"]), status === 409 ? /Another demo/ : /blocked/);
    assert.equal(client.running, false);
  }
  const client = new DemoClient(async () => ({ status: 502, json: async () => { throw Error("HTML"); } }));
  await assert.rejects(client.status(), /unreadable response \(HTTP 502\)/);
});
test("network failures release the client guard", async () => {
  const client = new DemoClient(async () => { throw Error("offline"); });
  await assert.rejects(client.run(["race"]), /offline/);
  assert.equal(client.running, false);
});
test("unknown scenarios and missing CSRF stop before writes", async () => {
  const log = [], client = new DemoClient(fetcher(log));
  for (const names of [[], ["reset-database"], null]) await assert.rejects(client.run(names), /known demo/);
  assert.equal(log.length, 0);
  let calls = 0;
  await assert.rejects(new DemoClient(async () => { calls++; return response({}); }).run(["race"]), /CSRF/);
  assert.equal(calls, 1);
});
test("rapid double clicks cannot launch a second execution", async () => {
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  const client = new DemoClient(async path => {
    if (path === "/api/csrf") { await wait; return response({ headerName: "X-CSRF-TOKEN", token: "token" }); }
    return response(recording("race"));
  });
  const first = client.run(["race"]);
  await assert.rejects(client.run(["race"]), /already running/);
  release(); await first;
  assert.equal(client.running, false);
});
test("mismatched, truncated or malformed results are rejected", async () => {
  for (const body of [{}, recording("expiry"), { ...recording("race"), passed: "true" }]) {
    await assert.rejects(new DemoClient(fetcher([], { race: response(body) })).run(["race"]), /invalid demo result/);
  }
  for (const mutate of [r => r.attempts.pop(), r => r.outcomes.RESERVED++, r => r.snapshots = [], r => r.checks = {},
    r => r.snapshots[1].inventory.id = "other", r => r.durationMs = NaN, r => r.completedAt = "invalid", r => r.attempts[0].durationMs = -1]) {
    const r = recording(); mutate(r); assert.throws(() => validateRecording(r), /invalid demo result/);
  }
});
test("activity requires exact order, complete snapshots and matching attempts", () => {
  for (const mutate of [r => delete r.activity, r => r.activity.pop(), r => r.activity[1].sequence = 4,
    r => r.activity[1].recordedAt = "bad", r => r.activity[1].durationMs = 99,
    r => r.activity[0].snapshotIndex = 1, r => r.activity[1].requestId = "req-500", r => r.activity[1].orderId = "other",
    r => r.activity[2].requestId = r.activity[1].requestId, r => delete r.activity[1].snapshotIndex]) {
    const r = recording(); mutate(r); assert.throws(() => validateActivity(r), /invalid demo result/);
  }
});
test("recordings can vary request and log counts without duplicating scenario rules", () => {
  const r = recording();
  r.attempts.pop();
  r.outcomes.INSUFFICIENT_STOCK--;
  r.activity.splice(-2, 1);
  r.activity.forEach((entry, i) => { entry.sequence = i + 1; });
  assert.equal(validateActivity(r), r);
  assert.equal(recordingPassed(r), true);
});
test("failed server checks and invalid stock balances are not successes", () => {
  for (const mutate of [r => r.passed = false, r => r.checks.extra = false,
    r => r.snapshots[1].inventory.reserved = 6, r => r.snapshots[1].inventory.available = -1]) {
    const r = recording(); mutate(r); assert.equal(recordingPassed(r), false);
  }
});
test("either payment/cancel winner is supported", () => {
  const r = recording("race");
  assert.equal(recordingPassed(r), true);
  r.attempts = [{ code: "INVALID_TRANSITION", orderId: null, durationMs: 5 }, { code: "CANCELLED", orderId: "order-1", durationMs: 5 }];
  r.outcomes = { CANCELLED: 1, INVALID_TRANSITION: 1 };
  Object.assign(r.snapshots[1].inventory, { available: 1, sold: 0 });
  assert.equal(recordingPassed(r), true);
});
test("progress inventory is explicitly derived; retries move stock only once", () => {
  const r = recording();
  assert.deepEqual(inventoryAt(r, r.activity.slice(0, 3)), { inventory: { ...r.snapshots[0].inventory, available: 3, reserved: 2 }, source: "Derived from replayed responses" });
  assert.equal(inventoryAt(r, r.activity).source, "Recorded snapshot");
  assert.deepEqual(inventoryAt(r, r.activity).inventory, r.snapshots.at(-1).inventory);
  const retries = recording("idempotency");
  const middle = inventoryAt(retries, retries.activity.slice(0, 17));
  assert.equal(middle.inventory.available, 7);
  assert.equal(middle.inventory.reserved, 3);
});
test("sequential scenarios change displayed inventory only at captured snapshots", () => {
  const r = recording("lifecycle");
  assert.equal(inventoryAt(r, r.activity.slice(0, 2)).inventory.available, 12);
  assert.equal(inventoryAt(r, r.activity.slice(0, 3)).inventory.available, 9);
  assert.equal(entryLevel({ code: "PAYMENT_FAILED" }), "ok");
  assert.equal(entryLevel({ code: "INSUFFICIENT_STOCK" }), "rejected");
  assert.equal(entryLevel({ code: "NO_CHANGE" }), "info");
});
function player() {
  const pending = new Map(), shown = [], states = [];
  let id = 0;
  const replay = new Replay(e => shown.push(e), r => states.push(r.state), cb => { pending.set(++id, cb); return id; }, key => pending.delete(key));
  const tick = () => { const [key, cb] = pending.entries().next().value; pending.delete(key); cb(); };
  return { replay, pending, shown, states, tick };
}
test("replay preserves server observation order without sorting successful rows first", () => {
  const { replay, shown, tick } = player();
  replay.load(["rejected-099", "accepted-001", "snapshot"]); replay.play();
  assert.deepEqual(shown, []);
  tick(); assert.deepEqual(shown, ["rejected-099"]);
  tick(); tick(); assert.deepEqual(shown, ["rejected-099", "accepted-001", "snapshot"]);
  assert.equal(replay.state, "complete");
});
test("pause and resume never duplicate entries; show-all cancels timers", () => {
  const { replay, shown, pending, tick } = player();
  replay.load([1, 2, 3]); replay.play(); tick(); replay.pause();
  assert.equal(pending.size, 0);
  replay.play(); tick(); replay.finish();
  assert.deepEqual(shown, [1, 2, 3]);
  assert.equal(pending.size, 0);
  replay.finish(); assert.deepEqual(shown, [1, 2, 3]);
});
test("changing scenarios invalidates already queued callbacks", () => {
  const { replay, shown, pending, tick } = player();
  replay.load(["old"]); replay.play(); const stale = [...pending.values()][0];
  replay.load(["new"]); replay.play(); stale();
  assert.deepEqual(shown, []); tick(); assert.deepEqual(shown, ["new"]);
});
