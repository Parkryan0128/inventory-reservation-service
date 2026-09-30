import { test, expect } from "@playwright/test";

test("manual stock changes control real purchases and survive mode switches and reload", async ({ page }) => {
  await page.goto("/");
  await page.locator("#manual-mode").click();
  await expect(page.locator('[data-action="BUY"]')).toBeEnabled();
  const product = await page.locator("#manual-product").innerText();
  await expect(page.locator("#speed-control")).toBeHidden();
  await expect(page.locator("#replay")).toBeHidden();
  await page.locator("#stock-quantity").fill("5");
  await page.locator('[data-action="REMOVE_STOCK"]').click();
  await expect(page.locator("#available")).toHaveText("0");
  await page.locator('[data-action="BUY"]').click();
  await expect(page.locator('.log-line[data-code="INSUFFICIENT_STOCK"]')).toHaveCount(1);
  await expect(page.locator("#manual-order")).toBeHidden();
  await page.locator("#stock-quantity").fill("2");
  await page.locator('[data-action="ADD_STOCK"]').click();
  await expect(page.locator("#available")).toHaveText("2");
  await page.locator('[data-action="BUY"]').click();
  await expect(page.locator("#manual-order-status")).toHaveText("RESERVED");
  await expect(page.locator("#reserved")).toHaveText("1");
  await expect(page.locator('[data-action="BUY"]')).toBeDisabled();
  await page.locator('[data-action="PAY"]').click();
  await expect(page.locator("#sold")).toHaveText("1");
  await expect(page.locator("#manual-order-status")).toHaveText("CONFIRMED");
  await page.locator('[data-action="BUY"]').click();
  await expect(page.locator("#manual-order-status")).toHaveText("RESERVED");
  await page.locator('[data-action="CANCEL"]').click();
  await expect(page.locator("#available")).toHaveText("1");
  await expect(page.locator("#reserved")).toHaveText("0");
  await page.locator("#scenarios-mode").click();
  await expect(page.locator("#speed-control")).toBeVisible();
  await page.locator("#manual-mode").click();
  await expect(page.locator("#manual-product")).toHaveText(product);
  await expect(page.locator("#sold")).toHaveText("1");
  await page.reload();
  await page.locator("#manual-mode").click();
  await expect(page.locator("#manual-order-status")).toHaveText("CANCELLED");
  await expect(page.locator("#manual-product")).toHaveText(product);
  await page.locator("#filter").selectOption("rejected");
  await expect(page.locator(".log-line:visible")).toHaveCount(1);
});

test("manual counters wait for a server response and controls fit on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 850 });
  await page.goto("/");
  await page.locator("#manual-mode").click();
  await expect(page.locator('[data-action="ADD_STOCK"]')).toBeEnabled();
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  await page.route("**/api/demo/manual/actions", async route => { await wait; await route.continue(); });
  try {
    await page.locator('[data-action="ADD_STOCK"]').click();
    await expect(page.locator('[data-action="ADD_STOCK"]')).toBeDisabled();
    await expect(page.locator("#available")).toHaveText("5");
  } finally { release(); }
  await expect(page.locator("#available")).toHaveText("6");
  const widths = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, viewport: innerWidth }));
  expect(widths.content).toBeLessThanOrEqual(widths.viewport);
  await expect(page.locator("#manual-product")).toBeVisible();
});
