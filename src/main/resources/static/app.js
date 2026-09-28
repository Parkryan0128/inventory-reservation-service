import { ApiError, Session } from "./session.js";

const $ = (id) => document.getElementById(id);
let activeSession = null;
const money = (cents, currency) =>
  new Intl.NumberFormat("en-US", { style: "currency", currency }).format(
    cents / 100,
  );

function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}

function notice(message, error = false) {
  $("notice").textContent = message;
  $("notice").classList.toggle("error", error);
}

function showResponse(method, path, result) {
  $("response").textContent =
    `${method} ${path}\n${JSON.stringify(result, null, 2)}`;
}

async function action(session, callback) {
  if (!session) return;
  try {
    await callback(session);
  } catch (error) {
    if (session !== activeSession || error.name === "AbortError") return;
    if (error instanceof ApiError)
      showResponse(error.method, error.path, error.result);
    notice(error.message, true);
  }
}

function newKey() {
  $("request-key").value = crypto.randomUUID();
}

function freshSku() {
  $("sku").value = "DEMO-" + crypto.randomUUID().slice(0, 8).toUpperCase();
}

function renderProducts(products) {
  $("products").replaceChildren();
  const selected = $("product").value;
  $("product").replaceChildren();
  if (!products.length)
    $("products").append(
      node("p", "No products yet. Connect as admin to create one.", "empty"),
    );
  for (const product of products) {
    const card = node("article", undefined, "product-card");
    const head = node("div", undefined, "product-head");
    const title = node("div");
    title.append(
      node("div", product.sku, "sku"),
      node("h3", product.name, "product-name"),
    );
    head.append(
      title,
      node("span", money(product.priceCents, product.currency), "price"),
    );
    const counts = node("div", undefined, "counts");
    for (const [key, label] of [
      ["available", "Available"],
      ["reserved", "Reserved"],
      ["sold", "Sold"],
    ]) {
      const item = node("div", undefined, "count");
      item.append(node("strong", product[key]), node("span", label));
      counts.append(item);
    }
    const bar = node("progress");
    bar.max = Math.max(1, product.initialStock);
    bar.value = product.available;
    bar.setAttribute(
      "aria-label",
      `${product.name}: ${product.available} of ${product.initialStock} available`,
    );
    card.append(head, counts, bar);
    $("products").append(card);
    const option = node("option", product.name);
    option.value = product.id;
    $("product").append(option);
  }
  if (products.some((product) => product.id === selected))
    $("product").value = selected;
}

function button(label, session, callback) {
  const element = node("button", label, "small-button subtle");
  element.addEventListener("click", () => action(session, callback));
  return element;
}

function renderOrders(session, orders) {
  $("orders").replaceChildren();
  if (!orders.length) {
    const row = node("tr");
    const cell = node(
      "td",
      "No orders yet. Reserve a product to start.",
      "empty",
    );
    cell.colSpan = 6;
    row.append(cell);
    $("orders").append(row);
  }
  for (const order of orders) {
    const row = node("tr");
    const id = node("td", order.id.slice(0, 8) + "…");
    id.title = order.id;
    id.append(node("span", order.ownerId, "owner"));
    const status = node("td");
    status.append(node("span", order.status, "badge " + order.status));
    const actions = node("td");
    if (order.status === "RESERVED") {
      if (order.ownerId === session.identity.username) {
        actions.append(
          button("Cancel", session, () =>
            mutate(session, `/api/orders/${order.id}/cancel`),
          ),
        );
      }
      if (session.isAdmin) {
        actions.append(
          button("Confirm payment", session, () =>
            mutate(session, `/api/admin/payments/${order.id}`, {
              success: true,
            }),
          ),
        );
        actions.append(
          button("Fail payment", session, () =>
            mutate(session, `/api/admin/payments/${order.id}`, {
              success: false,
            }),
          ),
        );
      }
    }
    row.append(
      id,
      node("td", order.quantity),
      node("td", money(order.totalPriceCents, order.currency)),
      status,
      node("td", new Date(order.expiresAt).toLocaleTimeString()),
      actions,
    );
    $("orders").append(row);
  }
}

async function metadata(session) {
  const id = $("product").value;
  if (!id) {
    $("metadata").textContent = "";
    return;
  }
  const info = await session.request(`/api/catalog/${id}`);
  session.assertOpen();
  if ($("product").value === id) {
    $("metadata").textContent =
      `${info.sku} · ${money(info.priceCents, info.currency)} each`;
  }
}

async function loadWorkspace(session) {
  const [products, orders, status] = await Promise.all([
    session.request("/api/products"),
    session.request(session.isAdmin ? "/api/admin/orders" : "/api/orders"),
    session.isAdmin ? session.request("/api/admin/status") : null,
  ]);
  session.assertOpen();
  renderProducts(products);
  renderOrders(session, orders);
  if (status) {
    const stats = node("div", undefined, "stats");
    for (const [key, label] of [
      ["pendingEvents", "Pending events"],
      ["auditReceipts", "Audit receipts"],
    ]) {
      const item = node("div");
      item.append(node("strong", status[key]), node("span", label, "muted"));
      stats.append(item);
    }
    $("events").replaceChildren(
      node(
        "p",
        status.eventsEnabled ? "Kafka relay enabled" : "Kafka relay disabled",
        "muted",
      ),
      stats,
    );
  }
  await metadata(session);
}

async function refresh(session) {
  if (!session.identity) return;
  if (session.refreshing) return session.refreshing;
  session.refreshing = loadWorkspace(session);
  try {
    await session.refreshing;
  } finally {
    session.refreshing = null;
  }
}

async function mutate(session, path, body, headers = {}) {
  const result = await session.request(path, "POST", body, headers);
  session.assertOpen();
  showResponse("POST", path, result);
  notice(
    result.status
      ? `Order ${result.id.slice(0, 8)} → ${result.status}`
      : "Product created.",
  );
  // An in-flight poll may predate this write. Follow it with a fresh read.
  try {
    await session.refreshing;
    await refresh(session);
  } catch (error) {
    session.assertOpen();
    notice(`Saved, but refresh failed: ${error.message}`, true);
  }
  return result;
}

function clearWorkspace() {
  for (const id of ["products", "product", "orders", "events"])
    $(id).replaceChildren();
  $("metadata").textContent = "";
  $("response").textContent = "No request yet.";
  $("replay").disabled = true;
  $("workspace").hidden = true;
  $("admin").hidden = true;
  $("login-section").hidden = false;
  $("disconnect").hidden = true;
  $("identity").textContent = "Disconnected";
}

$("login-form").addEventListener("submit", (event) => {
  event.preventDefault();
  activeSession?.close();
  const session = new Session($("account").value, $("password").value);
  activeSession = session;
  clearWorkspace();
  notice("Connecting…");
  action(session, async () => {
    try {
      await session.connect();
    } catch (error) {
      session.close();
      throw error;
    }
    session.assertOpen();
    $("password").value = "";
    $("identity").textContent =
      `${session.identity.username} / ${session.isAdmin ? "admin" : "customer"}`;
    $("login-section").hidden = true;
    $("workspace").hidden = false;
    $("disconnect").hidden = false;
    $("admin").hidden = !session.isAdmin;
    $("orders-caption").textContent = session.isAdmin
      ? "Latest 50 orders across accounts"
      : "Your latest 50 orders";
    newKey();
    freshSku();
    notice("Connected.");
    await refresh(session);
  });
});

$("disconnect").addEventListener("click", () => {
  activeSession?.close();
  activeSession = null;
  clearWorkspace();
  notice("Disconnected. Choose an account to connect again.");
});

$("reserve-form").addEventListener("submit", (event) => {
  event.preventDefault();
  action(activeSession, async (session) => {
    const request = {
      body: {
        productId: $("product").value,
        quantity: Number($("quantity").value),
      },
      key: $("request-key").value,
    };
    await mutate(session, "/api/orders", request.body, {
      "Idempotency-Key": request.key,
    });
    session.assertOpen();
    session.lastRequest = request;
    $("replay").disabled = false;
  });
});

$("replay").addEventListener("click", () =>
  action(activeSession, async (session) => {
    const request = session.lastRequest;
    if (request)
      await mutate(session, "/api/orders", request.body, {
        "Idempotency-Key": request.key,
      });
  }),
);
$("new-key").addEventListener("click", newKey);
$("product").addEventListener("change", () => action(activeSession, metadata));
$("refresh").addEventListener("click", () => action(activeSession, refresh));
$("product-form").addEventListener("submit", (event) => {
  event.preventDefault();
  action(activeSession, async (session) => {
    await mutate(session, "/api/products", {
      sku: $("sku").value,
      name: $("name").value,
      priceCents: Number($("price").value),
      currency: $("currency").value,
      stock: Number($("stock").value),
    });
    session.assertOpen();
    freshSku();
  });
});
setInterval(() => {
  if (!document.hidden) action(activeSession, refresh);
}, 5000);
