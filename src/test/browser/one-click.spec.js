import { test, expect } from "@playwright/test";

async function ready(page) {
  await page.goto("/");
  await expect(page.locator("#run")).toBeEnabled();
  await expect(page.getByRole("heading", { name: "Inventory Reservation System", exact: true })).toBeVisible();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
}
function responseFor(page, name) {
  return page.waitForResponse(r => r.url().endsWith(`/api/demo/run/${name}`) && r.request().method() === "POST");
}
async function execute(page, name) {
  await page.locator(`[data-scenario="${name}"]`).click();
  await page.locator("#speed").selectOption("fast");
  const pending = responseFor(page, name);
  await page.locator("#run").click();
  const result = await (await pending).json();
  await expect(page.locator("#playback-state")).toHaveText("Recording complete", { timeout: 30000 });
  return result;
}

test("real contention results fill the terminal progressively in recorded order", async ({ page }) => {
  const errors = [], writes = [];
  page.on("pageerror", e => errors.push(e.message));
  page.on("request", r => { if (r.url().includes("/api/demo/run/")) writes.push(r); });
  await ready(page);
  await page.locator("#speed").selectOption("slow");
  const pending = responseFor(page, "contention");
  await page.locator("#run").click();
  const result = await (await pending).json();
  expect(result.outcomes).toEqual({ RESERVED: 5, INSUFFICIENT_STOCK: 95 });
  await expect.poll(() => page.locator(".log-line").count()).toBeGreaterThan(0);
  expect(await page.locator(".log-line").count()).toBeLessThan(result.activity.length);
  await page.locator("#pause").click();
  const paused = await page.locator(".log-line").count();
  await page.waitForTimeout(300);
  expect(await page.locator(".log-line").count()).toBe(paused);
  await page.locator("#skip").click();
  await expect(page.locator("#verification")).toHaveText("Checks passed");
  await expect(page.locator('.log-line[data-code="RESERVED"]')).toHaveCount(5);
  await expect(page.locator('.log-line[data-code="INSUFFICIENT_STOCK"]')).toHaveCount(95);
  expect(await page.locator(".log-line").evaluateAll(rows => rows.map(r => Number(r.dataset.sequence))))
    .toEqual(result.activity.map(e => e.sequence));
  expect(await page.locator(".log-line").evaluateAll(rows => rows.map(r => r.dataset.request)))
    .toEqual(result.activity.map(e => e.requestId));
  await expect(page.locator("#available")).toHaveText("0");
  await expect(page.locator("#reserved")).toHaveText("5");
  await expect(page.locator("#sold")).toHaveText("0");
  expect(writes).toHaveLength(1);
  expect(writes[0].headers().authorization).toBeUndefined();
  expect(writes[0].headers()["x-csrf-token"]).toBeTruthy();
  expect(errors).toEqual([]);
  await page.locator("#follow").uncheck();
  await page.locator("#log").evaluate(el => el.scrollTop = 0);
  await page.screenshot({ path: test.info().outputPath("terminal-desktop.png"), fullPage: true });
});

test("all other presets show actual service records and database inventory", async ({ page }) => {
  await ready(page);
  for (const name of ["idempotency", "race", "lifecycle", "expiry"]) {
    const result = await execute(page, name);
    await expect(page.locator("#verification")).toHaveText("Checks passed");
    await expect(page.locator(".log-line")).toHaveCount(result.activity.length);
    for (const key of ["available", "reserved", "sold"]) {
      await expect(page.locator(`#${key}`)).toHaveText(String(result.snapshots.at(-1).inventory[key]));
    }
    if (name === "idempotency") {
      const ids = await page.locator('.log-line[data-code="RESERVED"]').evaluateAll(rows => rows.map(r => r.title));
      expect(ids).toHaveLength(16);
      expect(new Set(ids).size).toBe(1);
      await expect(page.locator('.log-line[data-code="IDEMPOTENCY_CONFLICT"]')).toHaveCount(1);
    }
    if (name === "expiry") await expect(page.locator('.log-line[data-code="NO_CHANGE"]')).toHaveCount(2);
  }
});

test("replay and filtering send no writes; rerunning creates a fresh product", async ({ page }) => {
  const writes = [];
  page.on("request", r => { if (r.url().includes("/api/demo/run/")) writes.push(r); });
  await ready(page);
  const first = await execute(page, "contention");
  await page.locator("#filter").selectOption("rejected");
  await expect(page.locator(".log-line:visible")).toHaveCount(95);
  await page.locator("#filter").selectOption("ok");
  await expect(page.locator(".log-line:visible")).toHaveCount(5);
  await page.locator("#filter").selectOption("snapshot");
  await expect(page.locator(".log-line:visible")).toHaveCount(2);
  await page.locator("#filter").selectOption("all");
  await page.locator("#replay").click();
  await expect(page.locator("#playback-state")).toHaveText("Recording complete");
  await expect(page.locator(".log-line")).toHaveCount(102);
  expect(writes).toHaveLength(1);
  const second = await execute(page, "contention");
  expect(first.snapshots[0].inventory.id).not.toBe(second.snapshots[0].inventory.id);
  expect(writes).toHaveLength(2);
});

test("switching presets cancels replay and reload never shows stale results", async ({ page }) => {
  await ready(page);
  await execute(page, "contention");
  await page.locator("#speed").selectOption("slow");
  await page.locator("#replay").click();
  await expect.poll(() => page.locator(".log-line").count()).toBeGreaterThan(0);
  await page.locator('[data-scenario="expiry"]').click();
  await page.waitForTimeout(300);
  await expect(page.locator(".log-line")).toHaveCount(0);
  await expect(page.locator("#playback-state")).toHaveText("No recording");
  await expect(page.locator("#result")).toBeHidden();
  await page.reload();
  await expect(page.locator("#run")).toBeEnabled();
  await expect(page.locator(".log-line")).toHaveCount(0);
});

test("errors and malformed records never appear as stock rejections or successful runs", async ({ page }) => {
  await ready(page);
  await page.route("**/api/demo/run/contention", route => route.fulfill({ status: 500, json: { detail: "Database unavailable" } }));
  await page.locator("#run").click();
  await expect(page.locator("#error")).toContainText("Database unavailable");
  await expect(page.locator("#result")).toBeHidden();
  await expect(page.locator(".log-line")).toHaveCount(0);
  await expect(page.locator("#run")).toBeEnabled();
  await page.unroute("**/api/demo/run/contention");
  await page.route("**/api/demo/run/contention", async route => {
    const response = await route.fetch();
    const result = await response.json();
    result.activity.pop();
    await route.fulfill({ response, json: result });
  });
  await page.locator("#run").click();
  await expect(page.locator("#error")).toContainText("invalid demo result");
  await expect(page.locator("#result")).toBeHidden();
  await page.unroute("**/api/demo/run/contention");
  await execute(page, "contention");
  await expect(page.locator("#verification")).toHaveText("Checks passed");
});

test("backend assertion failures remain visible even with HTTP 200", async ({ page }) => {
  await ready(page);
  await page.route("**/api/demo/run/race", async route => {
    const response = await route.fetch();
    const result = await response.json();
    result.passed = false;
    result.checks["Injected failure"] = false;
    await route.fulfill({ response, json: result });
  });
  await execute(page, "race");
  await expect(page.locator("#verification")).toHaveText("Checks failed");
  await expect(page.locator("#assertions .failed")).toContainText("Injected failure");
});

test("mobile terminal scrolls internally", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await execute(page, "lifecycle");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: test.info().outputPath("terminal-mobile.png"), fullPage: true });
});

test("reduced-motion preference does not override the selected speed or show-all control", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await ready(page);
  await page.locator("#speed").selectOption("slow");
  const pending = responseFor(page, "contention");
  await page.locator("#run").click();
  await pending;
  await expect.poll(() => page.locator(".log-line").count()).toBeGreaterThan(0);
  expect(await page.locator(".log-line").count()).toBeLessThan(102);
  await expect(page.locator("#playback-state")).toHaveText("Replaying recorded results");
  await page.locator("#skip").click();
  await expect(page.locator(".log-line")).toHaveCount(102);
  await expect(page.locator("#pause")).toBeDisabled();
  await expect(page.locator("#verification")).toHaveText("Checks passed");
});
