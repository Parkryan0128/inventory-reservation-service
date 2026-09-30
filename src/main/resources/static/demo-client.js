export const scenarios = ["contention", "idempotency", "lifecycle", "race", "expiry"];
const object = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const count = (v) => Number.isSafeInteger(v) && v >= 0;
const date = (v) => typeof v === "string" && Number.isFinite(Date.parse(v));
const invalid = () => { throw new Error("The server returned an invalid demo result. No verified recording is available."); };

export function validateRecording(r) {
  if (!object(r) || !scenarios.includes(r.scenario) || typeof r.passed !== "boolean" ||
      !count(r.durationMs) || !count(r.persistedOrders) || !date(r.completedAt) || typeof r.note !== "string" ||
      !object(r.checks) || !Object.keys(r.checks).length || !Object.values(r.checks).every(v => typeof v === "boolean") ||
      !object(r.outcomes) || !Object.values(r.outcomes).every(count) || !Array.isArray(r.attempts) ||
      !Array.isArray(r.snapshots) || !r.snapshots.length) invalid();
  for (const step of r.snapshots) {
    const p = step?.inventory;
    if (typeof step?.label !== "string" || !object(p) || typeof p.id !== "string" || !p.id ||
        ![p.available, p.reserved, p.sold, p.initialStock].every(Number.isSafeInteger) ||
        p.id !== r.snapshots[0].inventory.id || p.initialStock !== r.snapshots[0].inventory.initialStock) invalid();
  }
  const expected = { contention: 100, idempotency: 17, race: 2, lifecycle: 0, expiry: 0 };
  if (r.attempts.length !== expected[r.scenario]) invalid();
  const histogram = new Map();
  for (const a of r.attempts) {
    if (!object(a) || typeof a.code !== "string" || !a.code || !count(a.durationMs) ||
        !(a.orderId === null || typeof a.orderId === "string" && a.orderId.length)) invalid();
    histogram.set(a.code, (histogram.get(a.code) || 0) + 1);
  }
  if (histogram.size !== Object.keys(r.outcomes).length || [...histogram].some(([k, v]) => r.outcomes[k] !== v)) invalid();
  return r;
}

export function validateActivity(r) {
  validateRecording(r);
  const expected = { contention: 102, idempotency: 20, race: 5, lifecycle: 16, expiry: 7 };
  if (!Array.isArray(r.activity) || r.activity.length !== expected[r.scenario]) invalid();
  let snapshot = 0;
  const requests = new Set();
  for (const [i, e] of r.activity.entries()) {
    if (!object(e) || e.sequence !== i + 1 || !date(e.recordedAt) || !count(e.durationMs) || !count(e.quantity) ||
        ![e.actor, e.operation, e.code].every(v => typeof v === "string" && v.length) ||
        typeof e.requestId !== "string" || typeof e.key !== "string" ||
        !(e.orderId === null || typeof e.orderId === "string" && e.orderId.length)) invalid();
    if (e.snapshotIndex !== null) {
      if (e.snapshotIndex !== snapshot++ || !r.snapshots[e.snapshotIndex] || e.code !== "SNAPSHOT" || e.operation !== "inventory") invalid();
    } else {
      if (!e.requestId || requests.has(e.requestId)) invalid();
      requests.add(e.requestId);
      if (r.attempts.length && e.requestId.startsWith("req-")) {
        const a = r.attempts[Number(e.requestId.slice(4)) - 1];
        if (!a || e.code !== a.code || e.orderId !== a.orderId || e.durationMs !== a.durationMs) invalid();
      }
    }
  }
  if (snapshot !== r.snapshots.length || r.activity.at(-1).snapshotIndex !== snapshot - 1 ||
      r.attempts.length && [...requests].filter(id => id.startsWith("req-")).length !== r.attempts.length) invalid();
  return r;
}

export function summarize(r) {
  validateRecording(r);
  const p = r.snapshots.at(-1).inventory;
  const o = r.outcomes;
  const stock = (a, b, c) => p.available === a && p.reserved === b && p.sold === c;
  const unique = new Set(r.attempts.filter(a => a.orderId).map(a => a.orderId)).size;
  const conditions = {
    contention: o.RESERVED === 5 && o.INSUFFICIENT_STOCK === 95 && r.persistedOrders === 5 && unique === 5 && stock(0, 5, 0),
    idempotency: o.RESERVED === 16 && o.IDEMPOTENCY_CONFLICT === 1 && r.persistedOrders === 1 && unique === 1 && stock(7, 3, 0),
    race: o.INVALID_TRANSITION === 1 && r.persistedOrders === 1 && unique === 1 &&
      ((o.CONFIRMED === 1 && !o.CANCELLED && stock(0, 0, 1)) || (o.CANCELLED === 1 && !o.CONFIRMED && stock(1, 0, 0))),
    lifecycle: r.persistedOrders === 3 && stock(9, 0, 3),
    expiry: r.persistedOrders === 1 && stock(5, 0, 0),
  };
  const ok = r.passed && Object.values(r.checks).every(Boolean) && conditions[r.scenario] &&
    r.snapshots.every(({ inventory: s }) => s.available >= 0 && s.reserved >= 0 && s.sold >= 0 && s.available + s.reserved + s.sold === s.initialStock);
  const metrics = r.scenario === "contention"
    ? [["Reservations", o.RESERVED || 0], ["Rejected", o.INSUFFICIENT_STOCK || 0], ["Orders persisted", r.persistedOrders], ["Oversold units", Math.max(0, p.reserved + p.sold - p.initialStock)]]
    : r.scenario === "idempotency"
      ? [["Identical replies", o.RESERVED || 0], ["Changed payload rejected", o.IDEMPOTENCY_CONFLICT || 0], ["Orders persisted", r.persistedOrders], ["Reserved units", p.reserved]]
      : r.scenario === "race"
        ? [["Transitions accepted", (o.CONFIRMED || 0) + (o.CANCELLED || 0)], ["Transitions rejected", o.INVALID_TRANSITION || 0], ["Orders persisted", r.persistedOrders], ["Reserved units", p.reserved]]
        : [["Orders persisted", r.persistedOrders], ["Available units", p.available], ["Reserved units", p.reserved], ["Sold units", p.sold]];
  return { ok: Boolean(ok), metrics };
}

export function entryLevel(e) {
  if (["INSUFFICIENT_STOCK", "IDEMPOTENCY_CONFLICT", "INVALID_TRANSITION"].includes(e.code)) return "rejected";
  if (["SNAPSHOT", "DEADLINE_ADVANCED", "NO_CHANGE"].includes(e.code)) return "info";
  return "ok";
}

export function inventoryAt(r, entries) {
  let inventory = { ...r.snapshots[0].inventory };
  let source = "Recorded snapshot";
  const seen = new Set();
  for (const e of entries) {
    if (e.snapshotIndex !== null) {
      inventory = { ...r.snapshots[e.snapshotIndex].inventory };
      source = "Recorded snapshot";
    } else if (e.operation === "reserve" && e.code === "RESERVED" && e.orderId && !seen.has(e.orderId)) {
      seen.add(e.orderId);
      if (["contention", "idempotency"].includes(r.scenario)) {
        inventory.available -= e.quantity;
        inventory.reserved += e.quantity;
        source = "Derived from replayed responses";
      }
    }
  }
  return { inventory, source };
}

export const replayDelays = Object.freeze({ slow: 200, normal: 40, fast: 12 });

export class Replay {
  constructor(onEntry, onChange, schedule = (fn, ms) => globalThis.setTimeout(fn, ms), cancel = id => globalThis.clearTimeout(id)) {
    this.onEntry = onEntry;
    this.onChange = onChange;
    this.schedule = schedule;
    this.cancel = cancel;
    this.entries = [];
    this.position = 0;
    this.state = "idle";
    this.timer = null;
    this.generation = 0;
    this.speed = "normal";
  }
  cancelPending() {
    this.generation++;
    if (this.timer !== null) this.cancel(this.timer);
    this.timer = null;
  }
  stop() {
    this.cancelPending();
    this.state = "idle";
  }
  setSpeed(speed) {
    if (!Object.hasOwn(replayDelays, speed)) throw new RangeError("Unknown playback speed.");
    if (this.speed === speed) return;
    this.speed = speed;
    if (this.state === "playing") {
      this.cancelPending();
      this.next();
    }
  }
  load(entries) {
    this.stop();
    this.entries = [...entries];
    this.position = 0;
    this.state = "paused";
    this.onChange(this);
  }
  play() {
    if (this.state === "playing" || this.position >= this.entries.length) return;
    this.state = "playing";
    this.onChange(this);
    this.next();
  }
  next() {
    if (this.state !== "playing" || this.timer !== null) return;
    const generation = this.generation;
    this.timer = this.schedule(() => {
      if (generation !== this.generation || this.state !== "playing") return;
      this.timer = null;
      this.onEntry(this.entries[this.position++]);
      if (generation !== this.generation) return;
      if (this.position === this.entries.length) this.state = "complete";
      this.onChange(this);
      if (this.state === "playing") this.next();
    }, replayDelays[this.speed]);
  }
  pause() {
    if (this.state !== "playing") return;
    this.stop();
    this.state = "paused";
    this.onChange(this);
  }
  finish() {
    this.stop();
    while (this.position < this.entries.length) this.onEntry(this.entries[this.position++]);
    this.state = "complete";
    this.onChange(this);
  }
}

export class DemoClient {
  constructor(fetcher = globalThis.fetch.bind(globalThis)) { this.fetcher = fetcher; this.running = false; }
  async request(path, options = {}) {
    const response = await this.fetcher(path, { credentials: "same-origin", cache: "no-store", signal: AbortSignal.timeout(120000), ...options });
    let body;
    try { body = await response.json(); }
    catch { throw new Error(`The server returned an unreadable response (HTTP ${response.status}).`); }
    if (!response.ok) throw new Error(response.status === 409 ? "Another demo is running. Try again shortly."
      : response.status === 403 ? "The request was blocked. Refresh this localhost page and retry."
        : body?.detail || body?.message || `Request failed (HTTP ${response.status}).`);
    return body;
  }
  status() { return this.request("/api/demo/status"); }
  async run(selected, onResult = () => {}, onStart = () => {}) {
    if (this.running) throw new Error("A demo is already running in this tab.");
    if (!Array.isArray(selected) || !selected.length || selected.some(s => !scenarios.includes(s))) throw new Error("Choose a known demo scenario.");
    this.running = true;
    const results = [];
    try {
      for (const name of selected) {
        onStart(name);
        try {
          const csrf = await this.request("/api/csrf");
          if (!csrf || typeof csrf.headerName !== "string" || !csrf.headerName || typeof csrf.token !== "string" || !csrf.token) throw new Error("Unable to obtain a CSRF token. Refresh the page.");
          const result = validateActivity(await this.request(`/api/demo/run/${name}`, { method: "POST", headers: { [csrf.headerName]: csrf.token } }));
          if (result.scenario !== name) invalid();
          results.push(result);
          onResult(name, result, null);
        } catch (error) { onResult(name, null, error); throw error; }
      }
      return results;
    } finally { this.running = false; }
  }
}
