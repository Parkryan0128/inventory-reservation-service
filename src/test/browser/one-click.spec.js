import { test, expect } from "@playwright/test";

async function ready(page) {
  await page.goto("/");
  await expect(page.locator("#run")).toBeEnabled();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
}

async function execute(page, scenario = "contention") {
  if (scenario !== "contention") await page.locator(`[data-scenario="${scenario}"]`).click();
  const pending = page.waitForResponse(response => response.url().endsWith(`/api/demo/run/${scenario}`) && response.request().method() === "POST");
  await page.locator("#run").click();
  const response = await pending;
  expect(response.status()).toBe(200);
  const result = await response.json();
  await expect(page.locator("#phase")).toHaveText("RECORDED");
  await expect(page.locator("#run")).toBeEnabled();
  return result;
}

test("flash sale shows actual per-customer outcomes and five reserved, not sold, units", async ({ page }, testInfo) => {
  const errors = [];
  const writes = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", request => { if (request.url().includes("/api/demo/run/")) writes.push(request); });
  await ready(page);
  await expect(page.locator("#summary")).toHaveText("Who gets a reservation?");
  await expect(page.locator("#inventory-mode")).toHaveText("Preset");
  await expect(page.locator(".request-tile[data-state=PENDING]")).toHaveCount(100);
  const result = await execute(page);
  expect(result.outcomes).toEqual({ RESERVED: 5, INSUFFICIENT_STOCK: 95 });
  expect(result.persistedOrders).toBe(5);
  expect(result.attempts).toHaveLength(100);
  await expect(page.locator("#summary")).toHaveText("5 reserved. 95 turned away.");
  await expect(page.locator(".request-tile[data-state=RESERVED]")).toHaveCount(5);
  await expect(page.locator(".request-tile[data-state=INSUFFICIENT_STOCK]")).toHaveCount(95);
  await expect(page.locator("#available")).toHaveText("0");
  await expect(page.locator("#reserved")).toHaveText("5");
  await expect(page.locator("#sold")).toHaveText("0");
  const successIndex = result.attempts.findIndex(attempt => attempt.orderId);
  await page.locator(".request-tile").nth(successIndex).click();
  await expect(page.locator("#request-detail")).toContainText(result.attempts[successIndex].orderId);
  expect(writes).toHaveLength(1);
  expect(writes[0].headers().authorization).toBeUndefined();
  expect(writes[0].headers()["x-csrf-token"]).toBeTruthy();
  expect(errors).toEqual([]);
  await page.screenshot({ path: testInfo.outputPath("playground-desktop.png"), fullPage: true });
  await page.reload();
  await expect(page.locator("#phase")).toHaveText("READY");
  await expect(page.locator("#recording")).toBeHidden();
  await expect(page.locator("#raw")).toHaveText("No response yet.");
});

test("either race winner is displayed and repeated runs use fresh products", async ({ page }) => {
  await ready(page);
  const ids = [];
  for (let i = 0; i < 2; i++) {
    const result = await execute(page, "race");
    ids.push(result.snapshots[0].inventory.id);
    const paymentWon = result.outcomes.CONFIRMED === 1;
    await expect(page.locator(paymentWon ? "#payment-result" : "#cancel-result")).toHaveClass(/winner/);
    await expect(page.locator(paymentWon ? "#cancel-result" : "#payment-result")).toContainText("INVALID_TRANSITION");
    await expect(page.locator("#reserved")).toHaveText("0");
    await expect(page.locator("#sold")).toHaveText(paymentWon ? "1" : "0");
    await expect(page.locator("#available")).toHaveText(paymentWon ? "0" : "1");
  }
  expect(ids[0]).not.toBe(ids[1]);
});

test("duplicate requests expose one shared order ID and a separate payload conflict", async ({ page }) => {
  await ready(page);
  const result = await execute(page, "idempotency");
  expect(result.persistedOrders).toBe(1);
  expect(new Set(result.attempts.slice(0, 16).map(attempt => attempt.orderId)).size).toBe(1);
  await expect(page.locator("#summary")).toHaveText("16 replies. 1 order.");
  await expect(page.locator("#available")).toHaveText("7");
  await expect(page.locator("#reserved")).toHaveText("3");
  await expect(page.locator(".request-tile[data-state=RESERVED]")).toHaveCount(16);
  await page.locator(".request-tile").nth(0).click();
  await expect(page.locator("#request-detail")).toContainText(result.attempts[0].orderId);
  await page.locator(".request-tile").nth(16).click();
  await expect(page.locator("#request-detail")).toContainText("IDEMPOTENCY_CONFLICT");
  await expect(page.locator("#request-detail")).toContainText("No order returned");
});

test("recorded lifecycle steps can be replayed without new writes, and switching stops playback", async ({ page }) => {
  const writes = [];
  page.on("request", request => { if (request.url().includes("/api/demo/run/")) writes.push(request); });
  await ready(page);
  const result = await execute(page, "lifecycle");
  expect(result.snapshots).toHaveLength(7);
  await expect(page.locator("#available")).toHaveText("9");
  await expect(page.locator("#sold")).toHaveText("3");
  await page.locator(".snapshot").nth(1).click();
  await expect(page.locator("#reserved")).toHaveText("3");
  await expect(page.locator("#sold")).toHaveText("0");
  await page.locator("#replay").click();
  await expect(page.locator("#available")).toHaveText("12");
  await expect(page.locator("#replay")).toHaveText("Pause replay");
  await expect(page.locator("#reserved")).toHaveText("3", { timeout: 3000 });
  await page.locator('[data-scenario="race"]').click();
  await page.waitForTimeout(1300);
  await expect(page.locator("#inventory-mode")).toHaveText("Preset");
  await expect(page.locator("#reserved")).toHaveText("1");
  await expect(page.locator("#recording")).toBeHidden();
  expect(writes).toHaveLength(1);
});

test("expiry shows held stock returning without pretending to wait for the scheduler", async ({ page }) => {
  await ready(page);
  const result = await execute(page, "expiry");
  expect(result.persistedOrders).toBe(1);
  await expect(page.locator("#summary")).toHaveText("2 held units. Returned to stock.");
  await expect(page.locator("#available")).toHaveText("5");
  await expect(page.locator("#reserved")).toHaveText("0");
  await expect(page.locator("#scene-note")).toContainText("advances only its own order's deadline");
});

test("an infrastructure error clears the prior result, makes no success claim, and allows retry", async ({ page }) => {
  await ready(page);
  await execute(page);
  await page.route("**/api/demo/run/contention", route => route.fulfill({ status: 500, json: { detail: "Database unavailable" } }));
  await page.locator("#run").click();
  await expect(page.locator("#phase")).toHaveText("ERROR");
  await expect(page.locator("#summary")).toHaveText("No verified result.");
  await expect(page.locator("#outcome-note")).toContainText("Database unavailable");
  await expect(page.locator("#recording")).toBeHidden();
  await expect(page.locator("#metrics strong")).toHaveText(["—", "—", "—"]);
  await expect(page.locator(".request-tile[data-state=RESERVED]")).toHaveCount(0);
  await expect(page.locator("#run")).toBeEnabled();
  await page.unroute("**/api/demo/run/contention");
  await execute(page);
});

test("failed assertions remain visible without a celebratory result", async ({ page }) => {
  await ready(page);
  await page.route("**/api/demo/run/race", async route => {
    const response = await route.fetch();
    const result = await response.json();
    result.passed = false;
    result.checks["Injected assertion failure"] = false;
    await route.fulfill({ response, json: result });
  });
  await page.locator('[data-scenario="race"]').click();
  await page.locator("#run").click();
  await expect(page.locator("#phase")).toHaveText("CHECK FAILED");
  await expect(page.locator("#summary")).toHaveText("The run needs attention.");
  await page.locator("#backend > summary").click();
  await expect(page.locator("#assertions .failed")).toContainText("Injected assertion failure");
});

test("rapid clicks cannot submit twice or switch the scenario mid-request", async ({ page }) => {
  await ready(page);
  let release;
  const hold = new Promise(resolve => { release = resolve; });
  let writes = 0;
  await page.route("**/api/demo/run/contention", async route => { writes++; await hold; await route.continue(); });
  try {
    await page.locator("#run").evaluate(button => { button.click(); button.click(); });
    await expect(page.locator("#phase")).toHaveText("RUNNING");
    await expect(page.locator('[data-scenario="race"]')).toBeDisabled();
    await expect(page.locator("#run")).toBeDisabled();
    await expect.poll(() => writes).toBe(1);
  } finally { release(); }
  await expect(page.locator("#phase")).toHaveText("RECORDED");
  expect(writes).toBe(1);
});

test("mobile layout fits, recorded results remain inspectable, and the manual workspace still works", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await execute(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("playground-mobile.png"), fullPage: true });
  await page.getByRole("link", { name: "Manual API workspace" }).click();
  await expect(page.locator("#login-form")).toBeVisible();
});
