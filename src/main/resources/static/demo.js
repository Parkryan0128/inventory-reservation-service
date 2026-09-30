import { DemoClient, Replay, entryLevel, inventoryAt, summarize } from "./demo-client.js";

const presets = {
  contention: {
    title: "Concurrent reservations", stock: [5, 0, 0],
    description: "100 customers each try to reserve one unit of the same product. Only 5 units are available. The server submits the calls to a pool of 16 workers so they compete for stock.",
    config: [["Customers / calls", "100"], ["Server workers", "16"], ["Initial stock", "5 units"], ["Quantity", "1 per call"]],
    mechanism: "The reservation transaction locks the product row before changing stock. A rejected reservation must not create an order or reduce inventory.",
    expected: "5 reservations, 95 insufficient-stock rejections and 5 persisted orders. Final stock: 0 available, 5 reserved, 0 sold. Reserved is not yet paid.",
  },
  idempotency: {
    title: "Duplicate requests", stock: [10, 0, 0],
    description: "One customer retries the same 3-unit reservation 16 times with the same idempotency key. After those calls finish, one more call reuses the key but changes the quantity to 4.",
    config: [["Identical calls", "16"], ["Changed payload", "1 call"], ["Server workers", "16"], ["Initial stock", "10 units"]],
    mechanism: "The same customer, key and payload must return the same order. A changed payload must be rejected instead of creating another reservation.",
    expected: "16 replies with one shared order ID, then IDEMPOTENCY_CONFLICT. Only 3 units held. Final stock: 7 available, 3 reserved, 0 sold.",
  },
  race: {
    title: "Payment vs cancel", stock: [0, 1, 0],
    description: "Setup reserves the only unit. Two workers then call payment confirmation and cancellation against that same order at the same time.",
    config: [["Competing calls", "2"], ["Server workers", "2"], ["Shared orders", "1"], ["Starting state", "RESERVED"]],
    mechanism: "Both operations need the order lock. Only one terminal state can win; the competing operation must not sell and return the same unit.",
    expected: "One CONFIRMED or CANCELLED result and one INVALID_TRANSITION. Final stock is either 0 / 0 / 1 or 1 / 0 / 0. Either winner is valid.",
  },
  lifecycle: {
    title: "Order lifecycle", stock: [12, 0, 0],
    description: "Run three orders sequentially: reserve 3 and confirm payment twice; reserve 2 and cancel twice; reserve 2 and fail payment twice. Each service call appears in the log.",
    config: [["Orders", "3"], ["Service calls", "9"], ["Execution", "Sequential"], ["Initial stock", "12 units"]],
    mechanism: "Repeating a terminal operation must be safe. Payment moves reserved stock to sold; cancellation and payment failure return reserved stock to available.",
    expected: "One CONFIRMED, one CANCELLED and one PAYMENT_FAILED order. Repeated calls do not move stock again. Final stock: 9 available, 0 reserved, 3 sold.",
  },
  expiry: {
    title: "Reservation expiry", stock: [3, 2, 0],
    description: "Setup holds 2 of 5 units. First try expiry before the deadline, then advance only this generated order's deadline into the past. Run expiry and repeat it once more.",
    config: [["Held units", "2 of 5"], ["Expiry calls", "3"], ["Deadline", "Fast-forward fixture"], ["Execution", "Sequential"]],
    mechanism: "An unexpired reservation must remain held. An expired reservation must release its stock only once. The fixture does not change other orders or the global clock.",
    expected: "NO_CHANGE → deadline advanced → EXPIRED → NO_CHANGE. Final stock: 5 available, 0 reserved, 0 sold. No two-minute wait or real payment.",
  },
};
const $ = id => document.getElementById(id);
const client = new DemoClient();
let selected = "contention";
let result = null;
let busy = false;
let connected = false;
let serverBusy = false;
let shown = [];
const replay = new Replay(appendEntry, playbackChanged);
replay.delay = () => {
  if ($("speed").value === "fast") return 12;
  if ($("speed").value === "slow") return 200;
  return result?.activity.length > 25 ? 40 : 220;
};

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}
function renderValues(node, entries) {
  node.replaceChildren(...entries.map(([key, value]) => {
    const item = element("div");
    item.append(element("dt", key), element("dd", String(value)));
    return item;
  }));
}
function inventory(p, caption) {
  for (const key of ["available", "reserved", "sold"]) $(key).textContent = p[key];
  $("stock-caption").textContent = caption;
}
function controls() {
  $("run").disabled = busy || !connected || serverBusy || replay.state === "playing";
  $("run").textContent = busy ? "Running…" : "Run scenario";
  document.querySelectorAll("[data-scenario]").forEach(b => { b.disabled = busy; });
  $("pause").disabled = !result || busy || replay.state === "complete" || replay.state === "idle";
  $("pause").textContent = replay.state === "playing" ? "Pause" : "Resume";
  $("skip").disabled = !result || busy || replay.state === "complete";
  $("replay").disabled = !result || busy || replay.state === "playing";
}
function clearLog() {
  shown = [];
  $("log-lines").replaceChildren();
  $("log-placeholder").hidden = false;
  $("log-placeholder").textContent = "Run a scenario to record backend activity.";
  $("log-progress").textContent = "0 entries";
  $("log-counts").textContent = "0 successful · 0 rejected";
  $("follow").checked = true;
}
function choose(name) {
  if (busy) return;
  replay.stop();
  result = null;
  selected = name;
  clearLog();
  $("filter").value = "all";
  $("result").hidden = true;
  $("error").hidden = true;
  $("raw").textContent = "No response yet.";
  $("phase").textContent = "Ready";
  $("playback-state").textContent = "No recording";
  $("technical-note").textContent = "One browser request starts the scenario. Server workers invoke real transactional services. Activity rows describe service results, not HTTP response codes or SQL lock events.";
  const preset = presets[name];
  $("scenario-title").textContent = preset.title;
  $("description").textContent = preset.description;
  $("mechanism").textContent = preset.mechanism;
  $("expected").textContent = preset.expected;
  renderValues($("configuration"), preset.config);
  inventory({ available: preset.stock[0], reserved: preset.stock[1], sold: preset.stock[2] }, "Preset preview · no database changes yet.");
  document.querySelectorAll("[data-scenario]").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.scenario === name)));
  controls();
}
function matches(e) {
  const filter = $("filter").value;
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
  inventory(stock.inventory, `${stock.source} · historical data for this run`);
  $("log-progress").textContent = `${shown.length} / ${result.activity.length} recorded entries`;
  $("log-counts").textContent = `${shown.filter(e => entryLevel(e) === "ok").length} successful · ${shown.filter(e => entryLevel(e) === "rejected").length} rejected`;
  if ($("follow").checked) $("log").scrollTop = $("log").scrollHeight;
}
function showResult() {
  if (!result) return;
  const summary = summarize(result);
  $("result").hidden = false;
  $("verification").textContent = summary.ok ? "Checks passed" : "Checks failed";
  $("verification").className = summary.ok ? "pass" : "fail";
  renderValues($("metrics"), summary.metrics);
  const first = result.snapshots[0].inventory;
  const last = result.snapshots.at(-1).inventory;
  $("stock-table").replaceChildren(...["available", "reserved", "sold"].map(key => {
    const row = element("tr");
    row.append(element("td", key[0].toUpperCase() + key.slice(1)), element("td", first[key]), element("td", last[key]));
    return row;
  }));
  const checks = Object.entries(result.checks);
  if (!summary.ok && checks.every(([, v]) => v)) checks.push(["Returned counts and stock match the scenario", false]);
  $("assertions").replaceChildren(...checks.map(([label, passed]) => element("li", `${passed ? "✓" : "✕"} ${label}`, passed ? "" : "failed")));
  $("record-metadata").textContent = `Server execution: ${result.durationMs} ms · Completed: ${result.completedAt} · Product: ${last.id}`;
  inventory(last, `Final recorded snapshot · ${last.available} + ${last.reserved} + ${last.sold} = ${last.initialStock} units`);
}
function playbackChanged(player) {
  if (!result) return;
  const complete = player.state === "complete";
  $("playback-state").textContent = complete ? "Recording complete" : player.state === "playing" ? "Replaying recorded results" : "Replay paused";
  $("phase").textContent = complete ? "Completed" : player.state === "playing" ? "Replaying" : "Paused";
  if (complete) showResult();
  controls();
}
function startReplay() {
  if (!result || busy) return;
  replay.stop();
  clearLog();
  $("result").hidden = true;
  inventory(result.snapshots[0].inventory, `First recorded snapshot · ${result.snapshots[0].label}`);
  replay.load(result.activity);
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) replay.finish();
  else replay.play();
}
async function run() {
  if (busy || !connected || serverBusy || replay.state === "playing") return;
  choose(selected);
  busy = true;
  controls();
  $("phase").textContent = "Running on server";
  $("playback-state").textContent = "Waiting for server";
  $("log-placeholder").textContent = "> Executing scenario. Waiting for recorded backend results…";
  try {
    const [recorded] = await client.run([selected]);
    result = recorded;
    $("raw").textContent = JSON.stringify(result, null, 2);
    $("technical-note").textContent = result.note;
    busy = false;
    startReplay();
  } catch (error) {
    result = null;
    replay.stop();
    $("phase").textContent = "Error";
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
    $("connection").textContent = `${status.database} · ${serverBusy ? "Busy" : "Connected"}`;
    $("connection").className = "online";
    $("events").textContent = status.eventsEnabled
      ? `Kafka enabled. Application-wide outbox: ${status.pendingEvents} pending; ${status.auditReceipts} audit receipts. These counters are not per-scenario results.`
      : "Kafka delivery is disabled. The scenarios use the reservation service and database. Redis caches product metadata, not inventory counts.";
  } catch {
    connected = false;
    serverBusy = false;
    $("connection").textContent = "Backend unreachable · retrying";
    $("connection").className = "offline";
  }
  controls();
}
$("run").addEventListener("click", run);
$("pause").addEventListener("click", () => replay.state === "playing" ? replay.pause() : replay.play());
$("skip").addEventListener("click", () => { if (result && !busy) replay.finish(); });
$("replay").addEventListener("click", startReplay);
$("filter").addEventListener("change", () => {
  [...$("log-lines").children].forEach((row, i) => { row.hidden = !matches(shown[i]); });
});
$("follow").addEventListener("change", () => { if ($("follow").checked) $("log").scrollTop = $("log").scrollHeight; });
$("log").addEventListener("wheel", e => { if (e.deltaY < 0) $("follow").checked = false; }, { passive: true });
$("log").addEventListener("keydown", e => { if (["ArrowUp", "PageUp", "Home"].includes(e.key)) $("follow").checked = false; });
document.querySelectorAll("[data-scenario]").forEach(b => b.addEventListener("click", () => choose(b.dataset.scenario)));
choose(selected);
async function poll() { await refreshStatus(); setTimeout(poll, 4000); }
poll();
