import { DemoClient, ManualClient, Replay, entryLevel, inventoryAt, recordingPassed } from "./demo-client.js";

const presets = {
  contention: {
    title: "Concurrent reservations", stock: [5, 0, 0],
    description: "100 customers try to buy one unit each when only 5 units remain. The demo runs 100 reservation calls through 16 workers.",
    mechanism: "Each request locks the same product row before checking and updating stock. Requests that find no available stock are rejected.",
  },
  idempotency: {
    title: "Duplicate requests", stock: [10, 0, 0],
    description: "A customer retries the same 3-unit reservation 16 times. One additional request reuses the key with a different quantity.",
    mechanism: "Matching customer, key and payload return the existing order. A changed quantity with the same key is rejected.",
  },
  race: {
    title: "Payment vs cancel", stock: [0, 1, 0],
    description: "A customer cancels just as a payment succeeds. Two workers act on the same reserved order.",
    mechanism: "Both operations lock the order. One changes its state; the other finds a completed transition and is rejected.",
  },
  lifecycle: {
    title: "Order lifecycle", stock: [12, 0, 0],
    description: "Three orders take different paths: payment succeeds, the customer cancels, or payment fails. Each final action is repeated.",
    mechanism: "Payment moves held stock to sold. Cancellation and payment failure return it to available. Repeating the same action leaves stock unchanged.",
  },
  expiry: {
    title: "Reservation expiry", stock: [3, 2, 0],
    description: "A customer reserves 2 units but does not pay. By default the hold expires after 2 minutes; this demo advances only that order’s deadline.",
    mechanism: "Early expiry does nothing. After the deadline, the expiry service releases the 2 units. Retrying expiry makes no further change.",
  },
};
const $ = id => document.getElementById(id);
const client = new DemoClient();
const manualClient = new ManualClient();
let mode = "scenarios";
let manualState = null;
let manualBusy = false;
let manualAvailable = false;
let manualEpoch = 0;
let manualReadyAt = 0;
let manualCooldownTimer;
let manualLogVersion = "";
let selected = "contention";
let result = null;
let busy = false;
let connected = false;
let serverBusy = false;
let shown = [];
const replay = new Replay(appendEntry, playbackChanged);
replay.setSpeed($("speed").value);

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function inventory(p, caption) {
  for (const key of ["available", "reserved", "sold"]) $(key).textContent = p[key];
  $("stock-caption").textContent = caption;
}
function controls() {
  const activePlayback = Boolean(result) && (replay.state === "playing" || replay.state === "paused");
  const complete = Boolean(result) && replay.state === "complete";
  $("run").disabled = busy || !connected || serverBusy || replay.state === "playing";
  $("run").textContent = busy ? "Running…" : "Run scenario";
  $("run").title = !connected ? "Backend unavailable" : serverBusy ? "Another scenario is running" : "";
  document.querySelectorAll("[data-scenario]").forEach(b => { b.disabled = busy; });
  $("pause").hidden = !activePlayback;
  $("pause").disabled = !activePlayback || busy;
  $("pause").textContent = replay.state === "playing" ? "Pause" : "Resume";
  $("skip").hidden = !activePlayback;
  $("skip").disabled = !activePlayback || busy;
  $("replay").hidden = !complete;
  $("replay").disabled = !complete || busy;
  $("speed-control").hidden = mode === "manual";
  $("snapshot-filter").hidden = mode === "manual";
  $("own-filter").hidden = mode !== "manual";
  document.querySelectorAll("[data-mode]").forEach(b => { b.disabled = busy || manualBusy; });
  const reserved = manualState?.order?.status === "RESERVED";
  document.querySelectorAll("[data-action]").forEach(b => {
    const action = b.dataset.action;
    b.disabled = manualBusy || performance.now() < manualReadyAt || !manualAvailable || !manualState ||
      (action === "BUY" && reserved) || (["PAY", "CANCEL"].includes(action) && !reserved);
  });
  $("stock-quantity").disabled = manualBusy;
  $("buy-quantity").disabled = manualBusy;
  $("manual-connect").hidden = manualAvailable || manualBusy;
}
function clearLog() {
  manualLogVersion = "";
  shown = [];
  $("log-lines").replaceChildren();
  $("log").scrollTop = 0;
  $("log-placeholder").hidden = false;
  $("log-placeholder").textContent = "Run a scenario to record backend activity.";
  $("log-progress").textContent = "0 entries";
  $("log-counts").textContent = "0 successful · 0 rejected";
}
function choose(name) {
  if (busy || mode !== "scenarios") return;
  replay.stop();
  result = null;
  selected = name;
  clearLog();
  $("filter").value = "all";
  $("result").hidden = true;
  $("error").hidden = true;
  $("playback-state").textContent = "No recording";
  const preset = presets[name];
  $("scenario-title").textContent = preset.title;
  $("description").textContent = preset.description;
  $("mechanism").textContent = preset.mechanism;
  inventory({ available: preset.stock[0], reserved: preset.stock[1], sold: preset.stock[2] }, "Preset stock");
  document.querySelectorAll("[data-scenario]").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.scenario === name)));
  controls();
}
function matches(e) {
  const filter = $("filter").value;
  if (mode === "manual") return filter === "all" || filter === "own" && e.actor === "You" || filter === e.level;
  return filter === "all" || filter === "snapshot" && e.snapshotIndex !== null || filter === entryLevel(e);
}
function appendEntry(e) {
  shown.push(e);
  $("log-placeholder").hidden = true;
  const row = element("div", undefined, "log-line");
  const level = entryLevel(e);
  row.dataset.sequence = e.sequence;
  row.dataset.code = e.code;
  row.dataset.level = level;
  row.dataset.request = e.requestId;
  row.hidden = !matches(e);
  const timestamp = new Date(e.recordedAt).toISOString().slice(11, 23);
  row.append(element("span", `${String(e.sequence).padStart(3, "0")} `, "log-sequence"), element("span", `${timestamp} `, "log-time"),
    element("span", `${({ ok: "OK", rejected: "REJECT", info: "INFO" }[level]).padEnd(6)} `, "log-level"),
    element("span", `${(e.requestId || "db").padEnd(10)} ${e.actor.padEnd(14)} ${e.operation.padEnd(17)} `),
    element("span", e.code.padEnd(22), "log-code"));
  let detail;
  if (e.snapshotIndex !== null) {
    const p = result.snapshots[e.snapshotIndex].inventory;
    detail = `available=${p.available} reserved=${p.reserved} sold=${p.sold}`;
  } else {
    detail = `${e.durationMs}ms${e.quantity ? ` qty=${e.quantity}` : ""}${e.key ? ` key=${e.key}` : ""}${e.orderId ? ` order=${e.orderId.slice(0, 8)}` : ""}`;
    row.title = e.orderId ? `Order ${e.orderId}` : "No order returned";
  }
  row.append(element("span", ` ${detail}`, "log-data"));
  $("log-lines").append(row);
  const stock = inventoryAt(result, shown);
  inventory(stock.inventory, stock.source);
  $("log-progress").textContent = `${shown.length} / ${result.activity.length} recorded entries`;
  $("log-counts").textContent = `${shown.filter(e => entryLevel(e) === "ok").length} successful · ${shown.filter(e => entryLevel(e) === "rejected").length} rejected`;
  if ($("follow").checked) $("log").scrollTop = $("log").scrollHeight;
}
function showResult() {
  if (!result) return;
  const passed = recordingPassed(result);
  $("result").hidden = false;
  $("verification").textContent = passed ? "Checks passed" : "Checks failed";
  $("verification").className = passed ? "pass" : "fail";
  const last = result.snapshots.at(-1).inventory;
  const checks = Object.entries(result.checks);
  if (!passed && checks.every(([, v]) => v)) checks.push(["Server checks and stock balance pass", false]);
  $("assertions").replaceChildren(...checks.map(([label, passed]) => element("li", `${passed ? "✓" : "✕"} ${label}`, passed ? "" : "failed")));
  $("check-details").open = !passed;
  $("record-metadata").textContent = `Server execution: ${result.durationMs} ms`;
  inventory(last, "Final recorded snapshot");
}
function playbackChanged(player) {
  if (!result) return;
  const complete = player.state === "complete";
  $("playback-state").textContent = complete ? "Recording complete" : player.state === "playing" ? "Replaying recorded results" : "Replay paused";
  if (complete) showResult();
  controls();
}
function startReplay() {
  if (!result || busy) return;
  replay.stop();
  clearLog();
  $("result").hidden = true;
  inventory(result.snapshots[0].inventory, "First recorded snapshot");
  replay.setSpeed($("speed").value);
  replay.load(result.activity);
  replay.play();
}
async function run() {
  if (mode !== "scenarios" || busy || !connected || serverBusy || replay.state === "playing") return;
  choose(selected);
  busy = true;
  controls();
  $("playback-state").textContent = "Waiting for server";
  $("log-placeholder").textContent = "> Executing scenario. Waiting for recorded backend results…";
  try {
    const [recorded] = await client.run([selected]);
    result = recorded;
    busy = false;
    startReplay();
  } catch (error) {
    result = null;
    replay.stop();
    $("playback-state").textContent = "No verified recording";
    $("log-placeholder").textContent = `> ERROR: ${error.message}`;
    $("error").textContent = `${error.message} A failed response does not mean all database work was rolled back. Running again creates fresh data.`;
    $("error").hidden = false;
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
  } catch {
    connected = false;
    serverBusy = false;
  }
  controls();
}

async function changeMode(next) {
  if (busy || manualBusy || next === mode) return;
  mode = next;
  manualEpoch++;
  replay.stop();
  result = null;
  clearLog();
  $("result").hidden = true;
  $("error").hidden = true;
  $("filter").value = "all";
  $("scenarios-panel").hidden = mode !== "scenarios";
  $("manual-panel").hidden = mode !== "manual";
  document.querySelectorAll("[data-mode]").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.mode === mode)));
  if (mode === "scenarios") choose(selected);
  else {
    inventory({ available: "—", reserved: "—", sold: "—" }, "Waiting for server");
    $("log-placeholder").textContent = "Opening shared inventory…";
    await openManual();
  }
  controls();
}

function renderManual(state) {
  manualState = state;
  manualAvailable = true;
  manualReadyAt = performance.now() + state.retryAfterMs;
  clearTimeout(manualCooldownTimer);
  if (state.retryAfterMs) manualCooldownTimer = setTimeout(controls, state.retryAfterMs + 10);
  inventory(state.inventory, `Shared stock · ${new Date(state.observedAt).toLocaleTimeString()}`);
  $("manual-product").textContent = `Product ${state.inventory.id}`;
  $("manual-order").hidden = !state.order;
  if (state.order) {
    $("manual-order-status").textContent = state.order.status;
    $("manual-order-id").textContent = `Order ${state.order.id}`;
    $("manual-order-detail").textContent = `${state.order.quantity} unit(s)` +
      (state.order.status === "RESERVED" ? ` · Hold ends at ${new Date(state.order.expiresAt).toLocaleTimeString()}` : "");
    $("manual-order-actions").hidden = state.order.status !== "RESERVED";
  }
  const version = `${state.streamId}:${state.visitorId}:${state.activity.at(-1)?.sequence || 0}`;
  if (manualLogVersion !== version) {
    manualLogVersion = version;
    const position = $("log").scrollTop;
    shown = state.activity;
    $("log-lines").replaceChildren(...state.activity.map(e => {
      const row = element("div", undefined, "log-line");
      row.dataset.sequence = e.sequence;
      row.dataset.code = e.code;
      row.dataset.level = e.level;
      row.dataset.actor = e.actor;
      row.hidden = !matches(e);
      const time = new Date(e.recordedAt).toISOString().slice(11, 23);
      const label = { ok: "OK", rejected: "REJECT", info: "INFO" }[e.level];
      row.append(element("span", `${String(e.sequence).padStart(3, "0")} `, "log-sequence"),
        element("span", `${time} `, "log-time"), element("span", `${label.padEnd(6)} `, "log-level"),
        element("span", `${e.actor.padEnd(17)} ${e.operation.padEnd(15)} `), element("span", e.code.padEnd(22), "log-code"));
      const p = e.inventory;
      const detail = `${e.quantity ? `qty=${e.quantity} ` : ""}available=${p.available} reserved=${p.reserved} sold=${p.sold}` +
        (e.order ? ` order=${e.order.id.slice(0, 8)} state=${e.order.status}` : "") +
        (e.level === "rejected" ? ` · ${e.message}` : "");
      row.append(element("span", ` ${detail}`, "log-data"));
      row.title = e.order ? `Order ${e.order.id}` : `Product ${p.id}`;
      return row;
    }));
    $("log").scrollTop = $("follow").checked ? $("log").scrollHeight : position;
  }
  $("log-placeholder").hidden = state.activity.length > 0;
  $("log-placeholder").textContent = "No activity since this server started. Buy or change the shared stock.";
  $("log-progress").textContent = `${state.activity.length} / 200 recent entries`;
  $("log-counts").textContent = `${state.activity.filter(e => e.level === "ok").length} successful · ${state.activity.filter(e => e.level === "rejected").length} rejected`;
  $("playback-state").textContent = "Shared activity · updates every 4s";
  controls();
}

function manualError(error) {
  manualAvailable = false;
  $("playback-state").textContent = "Server unavailable";
  $("stock-caption").textContent = manualState ? "Last known database snapshot" : "No database snapshot";
  $("error").textContent = `${error.message} The request was not retried. Reconnect to check the current state before continuing.`;
  $("error").hidden = false;
  controls();
}

async function openManual() {
  if (manualBusy) return;
  manualBusy = true;
  manualEpoch++;
  controls();
  $("playback-state").textContent = "Opening shared inventory";
  try {
    renderManual(await manualClient.open());
    $("error").hidden = true;
  } catch (error) { manualError(error); }
  finally { manualBusy = false; controls(); }
}

async function manualAction(action, quantity) {
  if (mode !== "manual" || manualBusy || performance.now() < manualReadyAt || !manualAvailable) return;
  manualBusy = true;
  manualEpoch++;
  controls();
  $("error").hidden = true;
  $("playback-state").textContent = "Waiting for server";
  try {
    const state = await manualClient.act(action, quantity);
    renderManual(state);
    if (state.result.level === "rejected") {
      $("error").textContent = state.result.message;
      $("error").hidden = false;
    }
  } catch (error) { manualError(error); }
  finally { manualBusy = false; controls(); }
}

async function refreshManual() {
  if (mode !== "manual" || manualBusy || !manualState || !manualAvailable) return;
  const epoch = manualEpoch;
  try {
    const state = await manualClient.state();
    if (mode === "manual" && epoch === manualEpoch) renderManual(state);
  } catch (error) {
    if (mode === "manual" && epoch === manualEpoch) manualError(error);
  }
}

document.querySelectorAll("[data-mode]").forEach(b => b.addEventListener("click", () => changeMode(b.dataset.mode)));
$("manual-connect").addEventListener("click", openManual);
$("stock-form").addEventListener("submit", event => {
  event.preventDefault();
  const action = event.submitter?.dataset.action;
  if (["ADD_STOCK", "REMOVE_STOCK"].includes(action)) manualAction(action, $("stock-quantity").valueAsNumber);
});
$("buy-form").addEventListener("submit", event => {
  event.preventDefault();
  manualAction("BUY", $("buy-quantity").valueAsNumber);
});
for (const action of ["PAY", "CANCEL"]) {
  document.querySelector(`[data-action="${action}"]`).addEventListener("click", () => manualAction(action, 1));
}
$("run").addEventListener("click", run);
$("pause").addEventListener("click", () => replay.state === "playing" ? replay.pause() : replay.play());
$("skip").addEventListener("click", () => { if (result && !busy) replay.finish(); });
$("replay").addEventListener("click", startReplay);
$("speed").addEventListener("change", () => replay.setSpeed($("speed").value));
$("filter").addEventListener("change", () => {
  [...$("log-lines").children].forEach((row, i) => { row.hidden = !matches(shown[i]); });
});
$("follow").addEventListener("change", () => { if ($("follow").checked) $("log").scrollTop = $("log").scrollHeight; });
$("log").addEventListener("wheel", e => { if (e.deltaY < 0) $("follow").checked = false; }, { passive: true });
$("log").addEventListener("keydown", e => { if (["ArrowUp", "PageUp", "Home"].includes(e.key)) $("follow").checked = false; });
document.querySelectorAll("[data-scenario]").forEach(b => b.addEventListener("click", () => choose(b.dataset.scenario)));
choose(selected);
async function poll() {
  if (mode === "manual") await refreshManual();
  else await refreshStatus();
  setTimeout(poll, 4000);
}
poll();
