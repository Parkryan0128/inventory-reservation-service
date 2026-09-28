import assert from "node:assert/strict";
import test from "node:test";
import { ApiError, Session } from "../../main/resources/static/session.js";

const json = (body) => new Response(JSON.stringify(body));

function deferred() {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

test("disconnect aborts an outstanding request and rejects a late response", async () => {
  const pending = deferred();
  let signal;
  const session = new Session("alice", "password", (_path, options) => {
    signal = options.signal;
    return pending.promise;
  });
  const request = session.request("/api/orders");
  session.close();
  assert.equal(signal.aborted, true);
  pending.resolve(json([{ ownerId: "alice" }]));
  await assert.rejects(request, { name: "AbortError" });
  await assert.rejects(session.request("/api/orders"), { name: "AbortError" });
});

test("disconnect while reading a response body cannot restore an old identity", async () => {
  const body = deferred();
  const reading = deferred();
  const session = new Session("alice", "password", async () => ({
    ok: true,
    text: () => {
      reading.resolve();
      return body.promise;
    },
  }));
  const connection = session.connect();
  await reading.promise;
  session.close();
  body.resolve(JSON.stringify({ username: "alice", roles: ["ROLE_CUSTOMER"] }));
  await assert.rejects(connection, { name: "AbortError" });
  assert.equal(session.identity, null);
});

test("a new connection uses its own credentials and CSRF token", async () => {
  const calls = [];
  const fetchRequest = async (path, options) => {
    calls.push({ path, ...options });
    if (path === "/api/me")
      return json({ username: "bob", roles: ["ROLE_CUSTOMER"] });
    if (path === "/api/csrf")
      return json({ headerName: "X-CSRF-TOKEN", token: "bob-token" });
    return json({ ownerId: "bob" });
  };
  const alice = new Session("alice", "alice-password", fetchRequest);
  alice.lastRequest = { key: "alice-key" };
  alice.close();
  const bob = new Session("bob", "bob-password", fetchRequest);
  await bob.connect();
  await bob.request(
    "/api/orders",
    "POST",
    { quantity: 1 },
    { "Idempotency-Key": "bob-key" },
  );
  assert.equal(bob.lastRequest, null);
  assert.equal(alice.lastRequest, null);
  assert.equal(
    calls.at(-1).headers.Authorization,
    "Basic " + btoa("bob:bob-password"),
  );
  assert.equal(calls.at(-1).headers["X-CSRF-TOKEN"], "bob-token");
  assert.equal(calls.at(-1).headers["Idempotency-Key"], "bob-key");
});

test("problem responses preserve the status, code and request for the UI", async () => {
  const session = new Session(
    "alice",
    "password",
    async () =>
      new Response(
        JSON.stringify({
          code: "INSUFFICIENT_STOCK",
          detail: "Not enough stock",
        }),
        { status: 409 },
      ),
  );
  await assert.rejects(
    session.request("/api/orders", "POST", { quantity: 2 }),
    (error) => {
      assert.ok(error instanceof ApiError);
      assert.equal(error.path, "/api/orders");
      assert.equal(error.method, "POST");
      assert.match(error.message, /409 · INSUFFICIENT_STOCK: Not enough stock/);
      return true;
    },
  );
});

test("an HTML proxy error produces a readable message rather than a JSON parse error", async () => {
  const session = new Session(
    "alice",
    "password",
    async () => new Response("<html>Bad gateway</html>", { status: 502 }),
  );
  await assert.rejects(
    session.request("/api/products"),
    /502 · Server returned a non-JSON response/,
  );
});
