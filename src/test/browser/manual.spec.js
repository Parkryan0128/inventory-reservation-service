import { test, expect } from "@playwright/test";

async function setAvailable(page, target) {
  for (let attempt = 0; attempt < 20; attempt++) {
    const current = Number(await page.locator("#available").innerText());
    if (current === target) return;
    const amount = Math.min(10, Math.abs(target - current));
    await page.locator("#stock-quantity").fill(String(amount));
    await page.locator(`[data-action="${target > current ? "ADD_STOCK" : "REMOVE_STOCK"}"]`).click();
    await expect(page.locator("#available")).toHaveText(String(current + Math.sign(target - current) * amount));
  }
  throw new Error("Manual fixture stock is too far from the target");
}

test("manual stock changes control real purchases and survive mode switches and reload", async ({ page }) => {
  await page.goto("/");
  await page.locator("#manual-mode").click();
  await expect(page.locator('[data-action="BUY"]')).toBeEnabled();
  await setAvailable(page, 5);
  const sold = Number(await page.locator("#sold").innerText());
  const product = await page.locator("#manual-product").innerText();
  await expect(page.locator("#speed-control")).toBeHidden();
  await expect(page.locator("#replay")).toBeHidden();
  await page.locator("#stock-quantity").fill("5");
  await page.locator('[data-action="REMOVE_STOCK"]').click();
  await expect(page.locator("#available")).toHaveText("0");
  await page.locator('[data-action="BUY"]').click();
  await expect(page.locator("#error")).toContainText("Not enough");
  await expect(page.locator("#manual-order")).toBeHidden();
  await page.locator("#stock-quantity").fill("2");
  await page.locator('[data-action="ADD_STOCK"]').click();
  await expect(page.locator("#available")).toHaveText("2");
  await page.locator('[data-action="BUY"]').click();
  await expect(page.locator("#manual-order-status")).toHaveText("RESERVED");
  await expect(page.locator("#reserved")).toHaveText("1");
  await expect(page.locator('[data-action="BUY"]')).toBeDisabled();
  await page.locator('[data-action="PAY"]').click();
  await expect(page.locator("#sold")).toHaveText(String(sold + 1));
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
  await expect(page.locator("#sold")).toHaveText(String(sold + 1));
  await page.reload();
  await page.locator("#manual-mode").click();
  await expect(page.locator("#manual-order-status")).toHaveText("CANCELLED");
  await expect(page.locator("#manual-product")).toHaveText(product);
  await page.locator("#filter").selectOption("rejected");
  await expect(page.locator('.log-line:visible[data-level="ok"]')).toHaveCount(0);
  await expect(page.locator('.log-line:visible[data-code="INSUFFICIENT_STOCK"]').first()).toBeVisible();
});

test("manual counters wait for a server response and controls fit on a phone", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 850 });
  await page.goto("/");
  await page.locator("#manual-mode").click();
  await expect(page.locator('[data-action="ADD_STOCK"]')).toBeEnabled();
  const available = Number(await page.locator("#available").innerText());
  let release;
  const wait = new Promise(resolve => { release = resolve; });
  await page.route("**/api/demo/manual/actions", async route => { await wait; await route.continue(); });
  try {
    await page.locator('[data-action="ADD_STOCK"]').click();
    await expect(page.locator('[data-action="ADD_STOCK"]')).toBeDisabled();
    await expect(page.locator("#available")).toHaveText(String(available));
  } finally { release(); }
  await expect(page.locator("#available")).toHaveText(String(available + 1));
  const widths = await page.evaluate(() => ({ content: document.documentElement.scrollWidth, viewport: innerWidth }));
  expect(widths.content).toBeLessThanOrEqual(widths.viewport);
  await expect(page.locator("#manual-product")).toBeVisible();
});

test("separate visitors share stock and activity while keeping their orders private", async ({ page, browser }) => {
  await page.goto("/");
  await page.locator("#manual-mode").click();
  await expect(page.locator('[data-action="BUY"]')).toBeEnabled();
  await setAvailable(page, 1);
  const context = await browser.newContext();
  try {
    const other = await context.newPage();
    await other.goto(page.url());
    await other.locator("#manual-mode").click();
    await expect(other.locator("#manual-product")).toHaveText(await page.locator("#manual-product").innerText());
    await page.locator('[data-action="BUY"]').click();
    await expect(page.locator("#manual-order-status")).toHaveText("RESERVED");
    await expect(other.locator("#available")).toHaveText("0", { timeout: 10000 });
    await expect(other.locator("#manual-order")).toBeHidden();
    await expect(other.locator('.log-line[data-code="RESERVED"][data-actor^="Visitor "]').last()).toBeVisible();
    await other.locator('[data-action="BUY"]').click();
    await expect(other.locator("#error")).toContainText("Not enough");
    await page.locator('[data-action="CANCEL"]').click();
    await expect(other.locator("#available")).toHaveText("1", { timeout: 10000 });
    await other.locator('[data-action="BUY"]').click();
    await expect(other.locator("#manual-order-status")).toHaveText("RESERVED");
    await expect(page.locator("#manual-order-status")).toHaveText("CANCELLED");
    await other.locator('[data-action="CANCEL"]').click();
    await expect(other.locator("#manual-order-status")).toHaveText("CANCELLED");
    await other.locator("#filter").selectOption("own");
    await expect(other.locator('.log-line:visible[data-actor^="Visitor "]')).toHaveCount(0);
    await expect(other.locator('.log-line:visible[data-code="CANCELLED"][data-actor="You"]')).toHaveCount(1);
  } finally { await context.close(); }
});
