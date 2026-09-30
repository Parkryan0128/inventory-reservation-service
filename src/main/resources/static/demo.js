import { DemoClient, validateRecording, summarize } from "./demo-client.js";

const presets = {
  contention: { title: "5 graphics cards.\n100 customers.", description: "Everyone wants the last few. Can the system reserve exactly five, without promising the same unit twice?", scene: "The rush for the last five", note: "One square per customer. Green means reserved; beige means no stock left.", button: "Send 100 customers", hint: "100 calls · 16 server workers · 1 unit each", stock: [5, 0, 0], count: 100 },
  race: { title: "One order.\nTwo opposite requests.", description: "Payment arrives just as the customer cancels. Only one can win. The same unit cannot be sold and returned to stock.", scene: "Payment vs cancellation", note: "The server reserves one unit, then races these two operations against that order.", button: "Race payment & cancellation", hint: "2 calls · 2 server workers · 1 shared order", stock: [0, 1, 0] },
  idempotency: { title: "16 retries.\nStill one reservation?", description: "The same customer retries a reservation again and again. A repeated request should return the same order, not reserve more stock.", scene: "One intent, repeated 16 times", note: "16 identical calls reserve 3 units. The final call changes the quantity using the same key.", button: "Send duplicate requests", hint: "16 identical calls + 1 changed payload", stock: [10, 0, 0], count: 17 },
  lifecycle: { title: "Stock moves.\nBut only once.", description: "Follow a purchase, a cancellation and a failed payment. Repeated callbacks must not sell or return the same units twice.", scene: "Checkout, cancellation & payment failure", note: "Three orders, three endings. Replay the recorded steps to follow each stock change.", button: "Run the checkout story", hint: "3 orders · repeated terminal operations", stock: [12, 0, 0] },
  expiry: { title: "Abandoned checkout.\nStock comes back.", description: "A customer holds two cards and never pays. Expiration makes those units available again instead of holding them forever.", scene: "An unpaid reservation expires", note: "This preset advances only its own order's deadline, then calls the real expiry service.", button: "Expire an unpaid reservation", hint: "Fast-forward fixture · no two-minute wait", stock: [3, 2, 0] },
};
const $ = (id) => document.getElementById(id);
const client = new DemoClient();
let selected = "contention";
let result = null;
let busy = false;
let connected = false;
let serverBusy = false;
let timer = null;
let snapshotIndex = 0;

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function controls() {
  $("run").disabled = busy || !connected || serverBusy;
  document.querySelectorAll("[data-scenario]").forEach((button) => { button.disabled = busy; });
  $("run").textContent = busy ? "Running on the server…" : presets[selected].button + " ↗";
}

function inventory(values, initial, caption, recorded = false) {
  ["available", "reserved", "sold"].forEach((key, index) => {
    $(key).textContent = values[index];
    const units = Array.from({ length: Math.min(40, Math.max(0, values[index])) }, () => element("span", undefined, "unit"));
    $("units-" + key).replaceChildren(...units);
  });
  $("inventory-mode").textContent = recorded ? "Recorded" : "Preset";
  $("stock-equation").textContent = `${values[0]} available + ${values[1]} reserved + ${values[2]} sold = ${initial} units`;
  $("stock-caption").textContent = caption;
}

function renderMetrics(entries) {
  $("metrics").replaceChildren(...entries.map(([label, value]) => {
    const node = element("div", undefined, "metric");
    node.append(element("strong", value), element("span", label));
    return node;
  }));
}

function renderVisual() {
  const preset = presets[selected];
  const visual = $("visual");
  visual.replaceChildren();
  $("request-detail").textContent = "No execution recorded yet.";
  if (preset.count) {
    const grid = element("div", undefined, "request-grid" + (selected === "idempotency" ? " retry-grid" : ""));
    grid.setAttribute("aria-label", selected === "contention" ? "Customer request outcomes" : "Repeated request outcomes");
    for (let index = 0; index < preset.count; index++) {
      const attempt = result?.attempts[index];
      const label = selected === "contention" ? `Customer ${index + 1}` : index === 16 ? "Changed payload" : `Retry ${index + 1}`;
      const tile = element("button", index === 16 && selected === "idempotency" ? "Δ" : String(index + 1).padStart(2, "0"), "request-tile");
      tile.type = "button";
      tile.disabled = !attempt;
      tile.dataset.state = attempt?.code || "PENDING";
      tile.setAttribute("aria-label", `${label}: ${attempt?.code || "not run"}`);
      tile.setAttribute("aria-pressed", "false");
      tile.title = `${label}: ${attempt?.code || "not run"}`;
      tile.addEventListener("click", () => {
        grid.querySelectorAll("button").forEach((button) => button.setAttribute("aria-pressed", String(button === tile)));
        $("request-detail").textContent = `${label} → ${attempt.code} · ${attempt.durationMs} ms in service · ${attempt.orderId ? "Order " + attempt.orderId : "No order returned"}`;
      });
      grid.append(tile);
    }
    const legend = element("div", undefined, "legend");
    legend.append(element("span", "Reserved", "accepted"), element("span", selected === "contention" ? "No stock left" : "Changed payload rejected", "rejected"));
    visual.append(grid, legend);
    $("request-detail").textContent = result ? "Select a square to inspect its response and order ID. Squares are in request-number order, not completion order." : selected === "contention" ? "Each square will show one customer's actual reservation result." : "Identical successful retries should all return the same order ID.";
    return;
  }
  if (selected === "race") {
    const board = element("div", undefined, "race-board");
    ["Payment", "Cancellation"].forEach((label, index) => {
      const attempt = result?.attempts[index];
      const won = attempt && attempt.code !== "INVALID_TRANSITION";
      const card = element("div", undefined, "race-action" + (attempt ? won ? " winner" : " loser" : ""));
      card.id = index === 0 ? "payment-result" : "cancel-result";
      card.append(element("span", attempt ? won ? "WINNER" : "REJECTED" : "READY", "eyebrow"), element("strong", label), element("span", attempt?.code || "Waiting to race", "action-code"));
      if (index === 1) board.append(element("span", "VS", "race-vs"));
      board.append(card);
    });
    visual.append(board, element("p", "Both operations target the same reserved unit.", "race-caption"));
    const orderId = result?.attempts.find((attempt) => attempt.orderId)?.orderId;
    $("request-detail").textContent = orderId ? `Shared order: ${orderId}. Either operation may win on the next run.` : "One wins the transition. The other must leave inventory unchanged.";
    return;
  }
  const stages = selected === "expiry"
    ? [["01", "Hold 2 units", "Payment never arrives"], ["02", "Advance deadline", "Demo fixture, not a timer"], ["03", "Release stock", "Expiry retried safely"]]
    : [["01", "Purchase", "Reserve 3 → pay twice"], ["02", "Cancel", "Reserve 2 → cancel twice"], ["03", "Payment fails", "Reserve 2 → fail twice"]];
  const journey = element("div", undefined, "journey-visual");
  stages.forEach(([number, title, note]) => {
    const stop = element("div", undefined, "journey-stop");
    stop.append(element("b", number), element("strong", title), element("span", note));
    journey.append(stop);
  });
  visual.append(journey, element("p", result ? "Executed. Explore the recorded stock snapshots below." : "Scenario plan · not executed yet", "journey-caption"));
  $("request-detail").textContent = selected === "expiry" ? "Only this generated order's deadline changes. Existing customer orders are untouched." : "The server records stock after each step. Repeating a terminal operation must not move stock again.";
}

function stopReplay() {
  if (timer !== null) clearTimeout(timer);
  timer = null;
  $("replay").textContent = "Replay stock changes";
}

function showSnapshot(index) {
  snapshotIndex = index;
  const step = result.snapshots[index];
  const p = step.inventory;
  inventory([p.available, p.reserved, p.sold], p.initialStock, `Step ${index + 1}/${result.snapshots.length}: ${step.label}. Captured during this run.`, true);
  $("snapshots").querySelectorAll("button").forEach((button, i) => button.setAttribute("aria-pressed", String(i === index)));
}

function recording() {
  $("recording").hidden = false;
  $("snapshots").replaceChildren(...result.snapshots.map((step, index) => {
    const button = element("button", undefined, "snapshot");
    button.type = "button";
    button.setAttribute("aria-pressed", "false");
    const p = step.inventory;
    button.append(element("strong", `STEP ${index + 1}`), element("span", step.label), element("small", `A ${p.available} / R ${p.reserved} / S ${p.sold}`));
    button.addEventListener("click", () => { stopReplay(); showSnapshot(index); });
    return button;
  }));
  showSnapshot(result.snapshots.length - 1);
}

function choose(name) {
  if (busy) return;
  stopReplay();
  selected = name;
  result = null;
  document.querySelectorAll("[data-scenario]").forEach((button) => button.setAttribute("aria-pressed", String(button.dataset.scenario === name)));
  const preset = presets[name];
  $("headline").replaceChildren(...preset.title.split("\n").flatMap((line, index) => index ? [document.createElement("br"), document.createTextNode(line)] : [document.createTextNode(line)]));
  $("description").textContent = preset.description;
  $("scene-title").textContent = preset.scene;
  $("scene-note").textContent = preset.note;
  $("run-hint").textContent = preset.hint;
  $("phase").textContent = "READY";
  $("phase").className = "tag";
  $("outcome").className = "outcome";
  $("summary").textContent = name === "contention" ? "Who gets a reservation?" : "Ready to try this scenario.";
  $("outcome-note").textContent = "Run it against the real backend. A fresh product is created each time.";
  $("recording").hidden = true;
  $("snapshots").replaceChildren();
  $("assertions").replaceChildren();
  $("raw").textContent = "No response yet.";
  $("record-metadata").textContent = "No execution recorded yet.";
  $("technical-note").textContent = "One browser request starts the scenario. The server executes real transactional services. Results are displayed when that execution completes.";
  inventory(preset.stock, preset.stock.reduce((a, b) => a + b, 0), "Preset preview. No database changes yet.");
  renderMetrics([["Orders created", "—"], ["Reserved units", "—"], ["Sold units", "—"]]);
  renderVisual();
  controls();
}

async function run() {
  if (busy || !connected || serverBusy) return;
  choose(selected);
  busy = true;
  controls();
  $("phase").textContent = "RUNNING";
  $("phase").className = "tag running";
  $("summary").textContent = "The backend is handling the scenario…";
  $("outcome-note").textContent = "Waiting for actual results. No outcomes are being simulated in the browser.";
  $("stock-caption").textContent = "Preset preview while the server runs. Actual stock appears when the response arrives.";
  try {
    const [recorded] = await client.run([selected]);
    validateRecording(recorded);
    result = recorded;
    const view = summarize(result);
    $("phase").textContent = view.ok ? "RECORDED" : "CHECK FAILED";
    $("phase").className = "tag";
    $("outcome").className = "outcome " + (view.ok ? "success" : "failure");
    $("summary").textContent = view.title;
    $("outcome-note").textContent = view.note;
    renderMetrics(view.metrics);
    renderVisual();
    recording();
    $("technical-note").textContent = result.note;
    $("record-metadata").textContent = `${result.persistedOrders} persisted orders · ${result.durationMs} ms total · Completed ${new Date(result.completedAt).toLocaleTimeString()} · Product ${result.snapshots[0].inventory.id}`;
    $("raw").textContent = JSON.stringify(result, null, 2);
    $("assertions").replaceChildren(...Object.entries(result.checks).map(([label, passed]) => element("li", `${passed ? "✓" : "✕"} ${label}`, passed ? "" : "failed")));
  } catch (error) {
    result = null;
    $("phase").textContent = "ERROR";
    $("phase").className = "tag";
    $("outcome").className = "outcome failure";
    $("summary").textContent = "No verified result.";
    $("outcome-note").textContent = `${error.message} A failed response does not prove the server made no changes. A retry uses fresh data.`;
  } finally {
    busy = false;
    controls();
    await refreshStatus();
  }
}

async function refreshStatus() {
  try {
    const status = await client.status();
    connected = true;
    serverBusy = status.busy;
    $("connection").textContent = `${status.database} · ${status.busy ? "Running a scenario" : "Connected"}`;
    $("connection").className = "connection online";
    $("database-name").textContent = status.database;
    $("events").textContent = status.eventsEnabled ? `Application-wide events: ${status.pendingEvents} pending in outbox · ${status.auditReceipts} audit receipts. These are not per-scenario results.` : "Kafka delivery is off. These scenarios use the reservation service and database; enabling Kafka is optional.";
  } catch {
    connected = false;
    serverBusy = false;
    $("connection").textContent = "Backend unreachable · retrying";
    $("connection").className = "connection offline";
  }
  controls();
}

$("replay").addEventListener("click", () => {
  if (timer !== null) { stopReplay(); return; }
  if (!result || busy) return;
  showSnapshot(0);
  $("replay").textContent = "Pause replay";
  const advance = () => {
    if (snapshotIndex < result.snapshots.length - 1) showSnapshot(snapshotIndex + 1);
    if (snapshotIndex < result.snapshots.length - 1) timer = setTimeout(advance, 1000);
    else stopReplay();
  };
  timer = setTimeout(advance, 1000);
});
$("run").addEventListener("click", run);
document.querySelectorAll("[data-scenario]").forEach((button) => button.addEventListener("click", () => choose(button.dataset.scenario)));
choose(selected);
async function poll() { await refreshStatus(); setTimeout(poll, 4000); }
poll();
