export const scenarios = ["contention", "idempotency", "lifecycle", "race", "expiry"];

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
          : body.detail || body.message || `Request failed (HTTP ${response.status}).`;
      throw new Error(message);
    }
    return body;
  }

  status() {
    return this.request("/api/demo/status");
  }

  async run(selected, onResult = () => {}, onStart = () => {}) {
    if (this.running) throw new Error("A demo is already running in this tab.");
    if (!selected.length || selected.some((name) => !scenarios.includes(name))) {
      throw new Error("Choose a known demo scenario.");
    }
    this.running = true;
    const results = [];
    try {
      for (const name of selected) {
        onStart(name);
        try {
          const csrf = await this.request("/api/csrf");
          const result = await this.request(`/api/demo/run/${name}`, {
            method: "POST",
            headers: { [csrf.headerName]: csrf.token },
          });
          if (result.scenario !== name || typeof result.passed !== "boolean" ||
              !Array.isArray(result.snapshots) || !result.checks) {
            throw new Error("The server returned an invalid demo result.");
          }
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
