import { test, expect } from "@playwright/test";

const names = ["contention", "idempotency", "lifecycle", "race", "expiry"];

async function ready(page) {
  await page.goto("/");
  await expect(page.locator("#run-all")).toBeEnabled();
  await expect(page.locator('input[type="password"]')).toHaveCount(0);
}

function responseFor(page, scenario) {
  return page.waitForResponse((response) => response.url().endsWith(`/api/demo/run/${scenario}`) && response.request().method() === "POST");
}

test("one click runs five real backend scenarios without credentials", async ({ page }) => {
  test.setTimeout(120000);
  const errors = [];
  const writes = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("request", (request) => {
    if (request.url().includes("/api/demo/run/")) writes.push(request);
  });
  await ready(page);
  const contention = responseFor(page, "contention");
  await page.locator("#run-all").click();
  const actual = await (await contention).json();
  expect(actual.outcomes).toEqual({ RESERVED: 25, INSUFFICIENT_STOCK: 95 });
  expect(actual.snapshots.at(-1).inventory).toMatchObject({ available: 0, reserved: 25, sold: 0, initialStock: 25 });
  await expect(page.locator("#summary")).toHaveText("5 / 5 checks passed", { timeout: 90000 });
  for (const name of names) await expect(page.locator(`#badge-${name}`)).toHaveText("PASS");
  await expect(page.locator("#progress")).toHaveAttribute("value", "5");
  expect(writes).toHaveLength(5);
  for (const request of writes) {
    expect(request.headers().authorization).toBeUndefined();
    expect(request.headers()["x-csrf-token"]).toBeTruthy();
  }
  expect(errors).toEqual([]);
  await page.reload();
  await expect(page.locator("#badge-contention")).toHaveText("NOT RUN");
  await expect(page.locator("#result-contention")).toBeEmpty();
});

test("individual checks can be repeated without sharing products", async ({ page }) => {
  await ready(page);
  const products = [];
  for (let index = 0; index < 2; index++) {
    const pending = responseFor(page, "race");
    await page.locator('[data-scenario="race"]').click();
    const result = await (await pending).json();
    products.push(result.snapshots[0].inventory.id);
    await expect(page.locator("#badge-race")).toHaveText("PASS");
    await expect(page.locator('[data-scenario="race"]')).toBeEnabled();
  }
  expect(products[0]).not.toBe(products[1]);
  await expect(page.locator("#badge-contention")).toHaveText("NOT RUN");
});

test("backend errors are shown, stop the suite and allow retry", async ({ page }) => {
  await ready(page);
  await page.route("**/api/demo/run/contention", (route) => route.fulfill({ status: 500, json: { detail: "Database unavailable" } }));
  await page.locator("#run-all").click();
  await expect(page.locator("#badge-contention")).toHaveText("ERROR");
  await expect(page.locator("#summary")).toContainText("not a pass");
  await expect(page.locator("#result-contention")).toContainText("Database unavailable");
  await expect(page.locator("#badge-idempotency")).toHaveText("NOT RUN");
  await expect(page.locator("#run-all")).toBeEnabled();
  await page.unroute("**/api/demo/run/contention");
  await page.locator('[data-scenario="contention"]').click();
  await expect(page.locator("#badge-contention")).toHaveText("PASS", { timeout: 30000 });
});

test("a measured assertion failure is never displayed as a pass", async ({ page }) => {
  await ready(page);
  await page.route("**/api/demo/run/race", async (route) => {
    const response = await route.fetch();
    const result = await response.json();
    result.passed = false;
    result.checks["Injected assertion failure"] = false;
    await route.fulfill({ response, json: result });
  });
  await page.locator('[data-scenario="race"]').click();
  await expect(page.locator("#badge-race")).toHaveText("FAIL");
  await expect(page.locator("#summary")).toHaveText("0 / 1 checks passed");
  await expect(page.locator("#result-race .error")).toContainText("Injected assertion failure");
});

test("result tables fit a mobile screen and manual workspace remains available", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await ready(page);
  await page.locator('[data-scenario="lifecycle"]').click();
  await expect(page.locator("#badge-lifecycle")).toHaveText("PASS");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("link", { name: "Manual API workspace" }).click();
  await expect(page.locator("#login-form")).toBeVisible();
});
