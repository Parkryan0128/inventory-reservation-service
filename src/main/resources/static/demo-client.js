export const scenarios = ["contention", "idempotency", "lifecycle", "race", "expiry"];

const object = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const count = (value) => Number.isSafeInteger(value) && value >= 0;

export function validateRecording(result) {
  const invalid = () => { throw new Error("The server returned an invalid demo result. No verified recording is available."); };
  if (!object(result) || !scenarios.includes(result.scenario) || typeof result.passed !== "boolean" ||
      !count(result.durationMs) || !count(result.persistedOrders) || typeof result.completedAt !== "string" ||
      !Number.isFinite(Date.parse(result.completedAt)) || typeof result.note !== "string" ||
      !object(result.checks) || !Object.keys(result.checks).length ||
      !Object.values(result.checks).every((value) => typeof value === "boolean") ||
      !object(result.outcomes) || !Object.values(result.outcomes).every(count) ||
      !Array.isArray(result.attempts) || !Array.isArray(result.snapshots) || !result.snapshots.length) invalid();
  for (const step of result.snapshots) {
    const p = step?.inventory;
    if (typeof step?.label !== "string" || !object(p) || typeof p.id !== "string" ||
        ![p.available, p.reserved, p.sold, p.initialStock].every(Number.isSafeInteger) ||
        p.id !== result.snapshots[0].inventory.id || p.initialStock !== result.snapshots[0].inventory.initialStock) invalid();
  }
  const expected = { contention: 100, idempotency: 17, race: 2, lifecycle: 0, expiry: 0 };
  if (result.attempts.length !== expected[result.scenario]) invalid();
  const histogram = new Map();
  for (const attempt of result.attempts) {
    if (!object(attempt) || typeof attempt.code !== "string" || !attempt.code || !count(attempt.durationMs) ||
        !(attempt.orderId === null || typeof attempt.orderId === "string")) invalid();
    histogram.set(attempt.code, (histogram.get(attempt.code) || 0) + 1);
  }
  if (histogram.size !== Object.keys(result.outcomes).length ||
      [...histogram].some(([code, value]) => result.outcomes[code] !== value)) invalid();
  return result;
}

export function summarize(result) {
  validateRecording(result);
  const final = result.snapshots.at(-1).inventory;
  const first = result.snapshots[0].inventory;
  const outcomes = result.outcomes;
  const ok = result.passed && Object.values(result.checks).every(Boolean) && result.snapshots.every(({ inventory: p }) =>
    p.available >= 0 && p.reserved >= 0 && p.sold >= 0 && p.available + p.reserved + p.sold === p.initialStock);
  let title;
  let note;
  let metrics;
  switch (result.scenario) {
    case "contention":
      title = `${outcomes.RESERVED || 0} reserved. ${outcomes.INSUFFICIENT_STOCK || 0} turned away.`;
      note = "Every available unit is held for payment. None have been sold yet.";
      metrics = [["Orders created", result.persistedOrders], ["No stock left", outcomes.INSUFFICIENT_STOCK || 0], ["Oversold units", Math.max(0, final.reserved + final.sold - final.initialStock)]];
      break;
    case "race":
      title = outcomes.CONFIRMED ? "Payment won. Cancellation rejected." : "Cancellation won. Payment rejected.";
      note = "The losing operation made no stock change. Run again: either operation can win.";
      metrics = [["Successful transitions", (outcomes.CONFIRMED || 0) + (outcomes.CANCELLED || 0)], ["Rejected transitions", outcomes.INVALID_TRANSITION || 0], ["Still reserved", final.reserved]];
      break;
    case "idempotency":
      title = `${outcomes.RESERVED || 0} replies. ${result.persistedOrders} order.`;
      note = "Identical retries returned the same order ID. Reusing the key with a changed quantity was rejected.";
      metrics = [["Identical replies", outcomes.RESERVED || 0], ["Orders created", result.persistedOrders], ["Units reserved", final.reserved]];
      break;
    case "lifecycle":
      title = "Purchased, cancelled, released.";
      note = "Paid units stay sold. Cancellation and payment failure return stock, even when callbacks repeat.";
      metrics = [["Orders created", result.persistedOrders], ["Sold units", final.sold], ["Available units", final.available]];
      break;
    case "expiry":
      title = `${first.reserved - final.reserved} held units. Returned to stock.`;
      note = "The unpaid order expired. Repeating expiry did not release the same units again.";
      metrics = [["Units released", first.reserved - final.reserved], ["Available units", final.available], ["Still reserved", final.reserved]];
      break;
  }
  return { ok, metrics, title: ok ? title : "The run needs attention.", note: ok ? note : "A backend assertion or stock invariant failed. Inspect the recorded values and the failed checks under the hood." };
}

export class DemoClient {
  constructor(fetcher = globalThis.fetch.bind(globalThis)) {
    this.fetcher = fetcher;
    this.running = false;
  }

  async request(path, options = {}) {
    const response = await this.fetcher(path, {
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(120000),
      ...options,
    });
    let body;
    try {
      body = await response.json();
    } catch {
      throw new Error(`The server returned an unreadable response (HTTP ${response.status}).`);
    }
    if (!response.ok) {
      const message = response.status === 409
        ? "Another demo is running. Wait a moment and try again."
        : response.status === 403
          ? "The demo request was blocked. Refresh this localhost page and retry."
          : body?.detail || body?.message || `Request failed (HTTP ${response.status}).`;
      throw new Error(message);
    }
    return body;
  }

  status() { return this.request("/api/demo/status"); }

  async run(selected, onResult = () => {}, onStart = () => {}) {
    if (this.running) throw new Error("A demo is already running in this tab.");
    if (!selected.length || selected.some((name) => !scenarios.includes(name))) throw new Error("Choose a known demo scenario.");
    this.running = true;
    const results = [];
    try {
      for (const name of selected) {
        onStart(name);
        try {
          const csrf = await this.request("/api/csrf");
          if (!csrf || typeof csrf.headerName !== "string" || typeof csrf.token !== "string") throw new Error("Unable to obtain a CSRF token. Refresh the page.");
          const result = validateRecording(await this.request(`/api/demo/run/${name}`, {
            method: "POST",
            headers: { [csrf.headerName]: csrf.token },
          }));
          if (result.scenario !== name) throw new Error("The server returned an invalid demo result.");
          results.push(result);
          onResult(name, result, null);
        } catch (error) {
          onResult(name, null, error);
          throw error;
        }
      }
      return results;
    } finally {
      this.running = false;
    }
  }
}
