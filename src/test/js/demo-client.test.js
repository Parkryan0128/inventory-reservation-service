import test from "node:test";
import assert from "node:assert/strict";
import { DemoClient, scenarios } from "../../main/resources/static/demo-client.js";

const response = (body, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body });
const result = (scenario, passed = true) => ({ scenario, passed, snapshots: [], checks: { expected: passed }, outcomes: {} });

function fetcher(log, overrides = {}) {
  return async (path, options) => {
    log.push({ path, options });
    if (path === "/api/csrf") return response({ headerName: "X-CSRF-TOKEN", token: "fresh-csrf" });
    const name = path.split("/").at(-1);
    return overrides[name] || response(result(name));
  };
}

test("one click runs every scenario sequentially with CSRF and no credentials embedded", async () => {
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

test("a failed backend assertion remains a failure and later checks still run", async () => {
  const client = new DemoClient(fetcher([], { contention: response(result("contention", false)) }));
  const results = await client.run(["contention", "race"]);
  assert.deepEqual(results.map((item) => item.passed), [false, true]);
});

test("transport errors stop the suite without retrying writes or fabricating passes", async () => {
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

test("malformed or mismatched results are not marked as passed", async () => {
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
