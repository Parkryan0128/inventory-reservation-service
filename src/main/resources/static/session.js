export class ApiError extends Error {
  constructor(method, path, status, result) {
    const detail = result?.detail ? `: ${result.detail}` : "";
    super(
      `${status} · ${result?.code || result?.title || "Request failed"}${detail}`,
    );
    this.method = method;
    this.path = path;
    this.result = result;
  }
}

// Each connection owns its credentials and pending requests. Closing it also
// invalidates responses that arrived just before fetch could be aborted.
export class Session {
  #authorization;
  #controller = new AbortController();
  #fetch;
  identity = null;
  csrf = null;
  lastRequest = null;
  refreshing = null;

  constructor(username, password, fetchRequest = globalThis.fetch) {
    const credentials = new TextEncoder().encode(`${username}:${password}`);
    this.#authorization = "Basic " + btoa(String.fromCharCode(...credentials));
    // Native browser fetch requires Window as its receiver.
    this.#fetch = fetchRequest.bind(globalThis);
  }

  get isAdmin() {
    return this.identity?.roles.includes("ROLE_ADMIN") ?? false;
  }

  assertOpen() {
    this.#controller.signal.throwIfAborted();
  }

  close() {
    this.#controller.abort();
    this.#authorization = "";
    this.identity = null;
    this.csrf = null;
    this.lastRequest = null;
  }

  async connect() {
    const identity = await this.request("/api/me");
    this.assertOpen();
    this.identity = identity;
    const csrf = await this.request("/api/csrf");
    this.assertOpen();
    this.csrf = csrf;
  }

  async request(path, method = "GET", body, extraHeaders = {}) {
    this.assertOpen();
    const headers = { ...extraHeaders, Authorization: this.#authorization };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (method !== "GET" && this.csrf)
      headers[this.csrf.headerName] = this.csrf.token;
    const response = await this.#fetch(path, {
      method,
      headers,
      credentials: "same-origin",
      signal: this.#controller.signal,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    this.assertOpen();
    let result;
    try {
      result = text ? JSON.parse(text) : null;
    } catch {
      throw new ApiError(method, path, response.status, {
        title: "Server returned a non-JSON response",
      });
    }
    if (!response.ok) throw new ApiError(method, path, response.status, result);
    return result;
  }
}
