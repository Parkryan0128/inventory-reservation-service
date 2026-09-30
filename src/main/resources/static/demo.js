import { DemoClient, scenarios } from "./demo-client.js";

const client = new DemoClient();
const $ = (id) => document.getElementById(id);
const definitions = {
  contention: ["01", "Concurrent reservations", "120 requests compete for 25 units using 16 server worker threads. The database must reject the excess."],
  idempotency: ["02", "Duplicate-request protection", "16 identical requests race with one idempotency key. Only one order and one stock reservation should exist."],
  lifecycle: ["03", "Payment, cancellation & failure", "Confirm, cancel and fail payments, then repeat each operation. Stock must move exactly once."],
  race: ["04", "Payment vs. cancellation", "Two threads try opposite transitions on the same order. Only one may win; either winner is valid."],
  expiry: ["05", "Reservation expiry", "Advance only a fresh demo order's deadline, then run the real expiry logic twice. No two-minute wait."],
};
let ready = false;
let completed = 0;

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function controls(disabled) {
  for (const button of document.querySelectorAll("button[data-scenario], #run-all")) {
    button.disabled = disabled;
  }
}

for (const name of scenarios) {
  const [number, title, description] = definitions[name];
  const card = node("article", undefined, "panel scenario");
  card.id = `card-${name}`;
  const heading = node("div", undefined, "card-heading");
  heading.append(node("span", number, "number"), node("span", "NOT RUN", "badge"));
  const badge = heading.lastChild;
  badge.id = `badge-${name}`;
  const button = node("button", "Run this check", "secondary");
  button.dataset.scenario = name;
  button.disabled = true;
  button.addEventListener("click", () => run([name]));
  const output = node("div", undefined, "result");
  output.id = `result-${name}`;
  card.append(heading, node("h2", title), node("p", description, "description"), button, output);
  $("scenarios").append(card);
}

function render(name, result, error) {
  const output = $(`result-${name}`);
  output.replaceChildren();
  const badge = $(`badge-${name}`);
  if (error) {
    badge.textContent = "ERROR";
    badge.className = "badge fail";
    output.append(node("p", error.message, "error"));
    return;
  }
  badge.textContent = result.passed ? "PASS" : "FAIL";
  badge.className = `badge ${result.passed ? "pass" : "fail"}`;
  output.append(node("p", `${result.durationMs.toLocaleString()} ms · measured on this run`, "timing"));
  const outcomes = node("div", undefined, "outcomes");
  for (const [code, count] of Object.entries(result.outcomes)) {
    const item = node("div");
    item.append(node("strong", count), node("span", code.replaceAll("_", " ")));
    outcomes.append(item);
  }
  output.append(outcomes);
  const table = node("table");
  const caption = node("caption", "Stock read back from the database");
  const head = node("tr");
  for (const label of ["Step", "Available", "Reserved", "Sold"]) head.append(node("th", label));
  const thead = node("thead");
  thead.append(head);
  const tbody = node("tbody");
  for (const snapshot of result.snapshots) {
    const row = node("tr");
    row.append(node("td", snapshot.label));
    for (const key of ["available", "reserved", "sold"]) row.append(node("td", snapshot.inventory[key]));
    tbody.append(row);
  }
  table.append(caption, thead, tbody);
  const wrap = node("div", undefined, "table-wrap");
  wrap.append(table);
  output.append(wrap);
  const checks = node("ul", undefined, "checks");
  for (const [label, passed] of Object.entries(result.checks)) {
    checks.append(node("li", `${passed ? "✓" : "✕"} ${label}`, passed ? "pass-text" : "error"));
  }
  output.append(checks, node("p", result.note, "hint"));
  const raw = node("details");
  raw.append(node("summary", "Inspect actual response & product ID"), node("pre", JSON.stringify(result, null, 2)));
  output.append(raw);
}

async function run(selected) {
  if (!ready || client.running) return;
  controls(true);
  completed = 0;
  $("progress").max = selected.length;
  $("progress").value = 0;
  for (const name of selected) {
    $(`result-${name}`).replaceChildren();
    $(`badge-${name}`).textContent = "QUEUED";
    $(`badge-${name}`).className = "badge";
  }
  try {
    const results = await client.run(selected, (name, result, error) => {
      render(name, result, error);
      if (result) $("progress").value = ++completed;
    }, (name) => {
      $(`badge-${name}`).textContent = "RUNNING";
      $(`badge-${name}`).className = "badge running";
      $("summary").textContent = `Running: ${definitions[name][1]}`;
      $("detail").textContent = `${completed} / ${selected.length} complete. Waiting for real backend results…`;
    });
    const passed = results.filter((result) => result.passed).length;
    $("summary").textContent = `${passed} / ${results.length} checks passed`;
    $("detail").textContent = passed === results.length
      ? "Every selected check matched its expected database state. Run again for a fresh sample."
      : "A check did not match expectations. Inspect its failed assertions below.";
  } catch (error) {
    $("summary").textContent = "Demo interrupted — not a pass";
    $("detail").textContent = `${error.message} A timed-out request may still be finishing on the server; no automatic retry was sent.`;
    for (const name of selected) {
      if ($(`badge-${name}`).textContent === "QUEUED") $(`badge-${name}`).textContent = "NOT RUN";
    }
  } finally {
    controls(false);
    await refreshStatus();
  }
}

async function refreshStatus() {
  try {
    const status = await client.status();
    $("database").textContent = status.database;
    $("pending").textContent = status.pendingEvents;
    $("receipts").textContent = status.auditReceipts;
    $("event-status").textContent = status.eventsEnabled
      ? "Kafka relay enabled. Delivery counters refresh every 3 seconds."
      : "Kafka disabled — outbox events are retained, not delivered. This is the default setup.";
    if (!ready) {
      ready = true;
      $("summary").textContent = "Ready. No login needed.";
      $("detail").textContent = "Run everything, or choose a single check below.";
    }
    controls(client.running || status.busy);
  } catch (error) {
    if (!ready) {
      $("summary").textContent = "Could not connect to the local demo";
      $("detail").textContent = "Keep Docker running and open http://127.0.0.1:8080/. This page retries automatically.";
    }
    $("event-status").textContent = "Event status unavailable — server connection failed.";
  }
}

$("run-all").addEventListener("click", () => run(scenarios));
await refreshStatus();
setInterval(() => { if (!document.hidden) void refreshStatus(); }, 3000);
