import { test, expect } from "@playwright/test";
import { recording } from "../js/fixtures.js";

async function ready(page) {
  await page.clock.install({ time: new Date("2026-01-01T12:00:00Z") });
  await page.route("**/api/demo/status", route => route.fulfill({ json: { busy: false } }));
  await page.route("**/api/csrf", route => route.fulfill({ json: { headerName: "X-CSRF-TOKEN", token: "test" } }));
  await page.route("**/api/demo/run/*", route => route.fulfill({ json: recording(route.request().url().split("/").at(-1)) }));
  await page.goto("/");
  await expect(page.locator("#run")).toBeEnabled();
  await page.clock.pauseAt(new Date("2026-01-01T12:01:00Z"));
}
async function start(page) {
  await page.locator("#run").click();
  await expect(page.locator("#playback-state")).toHaveText("Replaying recorded results");
}
const rows = page => page.locator(".log-line");
const scrollTop = page => page.locator("#log").evaluate(el => el.scrollTop);
const bottomGap = page => page.locator("#log").evaluate(el => el.scrollHeight - el.clientHeight - el.scrollTop);

for (const scenario of ["contention", "race"]) {
  test(`all speed settings apply from the first row of ${scenario}`, async ({ page }) => {
    await ready(page);
    for (const [speed, delay] of [["slow", 200], ["normal", 40], ["fast", 12]]) {
      await page.locator(`[data-scenario="${scenario}"]`).click();
      await page.locator("#speed").selectOption(speed);
      await start(page);
      await page.clock.runFor(delay - 1);
      await expect(rows(page)).toHaveCount(0);
      await page.clock.runFor(1);
      await expect(rows(page)).toHaveCount(1);
      await page.clock.runFor(delay);
      await expect(rows(page)).toHaveCount(2);
      await page.locator("#skip").click();
      await expect(rows(page)).toHaveCount(recording(scenario).activity.length);
      await expect(page.locator("#verification")).toHaveText("Checks passed");
    }
  });
}

test("changing speed replaces queued delays, preserves pause and does not duplicate rows", async ({ page }) => {
  let writes = 0;
  page.on("request", r => { if (r.url().includes("/api/demo/run/")) writes++; });
  await ready(page);
  await page.locator("#speed").selectOption("slow");
  await start(page);
  await page.clock.runFor(100);
  await expect(rows(page)).toHaveCount(0);
  await page.locator("#speed").selectOption("fast");
  await page.clock.runFor(11);
  await expect(rows(page)).toHaveCount(0);
  await page.clock.runFor(1);
  await expect(rows(page)).toHaveCount(1);
  await page.locator("#speed").selectOption("slow");
  await page.clock.runFor(199);
  await expect(rows(page)).toHaveCount(1);
  await page.clock.runFor(1);
  await expect(rows(page)).toHaveCount(2);
  await page.locator("#speed").selectOption("normal");
  await page.clock.runFor(40);
  await expect(rows(page)).toHaveCount(3);
  await page.locator("#pause").click();
  await page.locator("#speed").selectOption("fast");
  await page.clock.runFor(1000);
  await expect(rows(page)).toHaveCount(3);
  await expect(page.locator("#playback-state")).toHaveText("Replay paused");
  await page.locator("#pause").click();
  await page.clock.runFor(12);
  await expect(rows(page)).toHaveCount(4);
  await page.locator("#skip").click();
  await page.clock.runFor(1000);
  await expect(rows(page)).toHaveCount(102);
  expect(await rows(page).evaluateAll(elements => elements.map(e => Number(e.dataset.sequence))))
    .toEqual(recording().activity.map(e => e.sequence));
  expect(writes).toBe(1);
});

test("follow is on by default, but a pre-run opt-out survives execution and replay", async ({ page }) => {
  await ready(page);
  await expect(page.locator("#follow")).toBeChecked();
  await page.locator("#follow").uncheck();
  await page.locator("#speed").selectOption("fast");
  await start(page);
  await expect(page.locator("#follow")).not.toBeChecked();
  await page.clock.runFor(400);
  expect(await page.locator("#log").evaluate(el => el.scrollHeight > el.clientHeight)).toBe(true);
  expect(await scrollTop(page)).toBe(0);
  await page.locator("#follow").check();
  expect(await scrollTop(page)).toBeGreaterThan(0);
  await page.clock.runFor(120);
  expect(await bottomGap(page)).toBeLessThan(2);
  await page.locator("#follow").uncheck();
  const stoppedAt = await scrollTop(page);
  await page.clock.runFor(120);
  expect(await scrollTop(page)).toBe(stoppedAt);
  await page.locator("#skip").click();
  expect(await scrollTop(page)).toBe(stoppedAt);
  await page.locator("#replay").click();
  await page.clock.runFor(400);
  await expect(page.locator("#follow")).not.toBeChecked();
  expect(await scrollTop(page)).toBe(0);
  await page.locator("#skip").click();
  await page.locator('[data-scenario="race"]').click();
  await page.locator('[data-scenario="contention"]').click();
  await expect(page.locator("#follow")).not.toBeChecked();
  await expect(page.locator("#speed")).toHaveValue("fast");
  await start(page);
  await page.clock.runFor(400);
  expect(await scrollTop(page)).toBe(0);
  await page.locator("#skip").click();
  await page.locator("#follow").check();
  await start(page);
  await page.clock.runFor(400);
  await expect(page.locator("#follow")).toBeChecked();
  expect(await scrollTop(page)).toBeGreaterThan(0);
  expect(await bottomGap(page)).toBeLessThan(2);
});

test("manual upward scrolling stops following and does not get reset by a new run", async ({ page }) => {
  await ready(page);
  await page.locator("#speed").selectOption("fast");
  await start(page);
  await page.clock.runFor(400);
  await expect(page.locator("#follow")).toBeChecked();
  expect(await bottomGap(page)).toBeLessThan(2);
  await page.locator("#log").dispatchEvent("wheel", { deltaY: -100 });
  await expect(page.locator("#follow")).not.toBeChecked();
  await page.locator("#log").evaluate(el => { el.scrollTop = 0; });
  await page.clock.runFor(120);
  expect(await scrollTop(page)).toBe(0);
  await page.locator("#skip").click();
  await start(page);
  await page.clock.runFor(400);
  await expect(page.locator("#follow")).not.toBeChecked();
  expect(await scrollTop(page)).toBe(0);
});

test("the dashboard omits connection badges, redundant explanations, raw response and footer", async ({ page }) => {
  const errors = [];
  page.on("pageerror", e => errors.push(e.message));
  await ready(page);
  const removed = "#connection, #phase, #log-note, .technical, #technical-note, #events, #raw, footer";
  await expect(page.locator(removed)).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Manual API workspace" })).toHaveCount(0);
  await page.clock.runFor(4000);
  for (const scenario of ["contention", "race", "expiry"]) {
    await page.locator(`[data-scenario="${scenario}"]`).click();
    await start(page);
    await page.locator("#skip").click();
    await expect(page.locator(removed)).toHaveCount(0);
    await expect(page.locator("#verification")).toHaveText("Checks passed");
  }
  expect(errors).toEqual([]);
});


test("desktop hierarchy keeps the compact scenario sidebar beside a wider terminal", async ({ page }) => {
  await ready(page);
  const sidebar = await page.locator(".sidebar").boundingBox();
  const terminal = await page.locator(".terminal").boundingBox();
  expect(sidebar).not.toBeNull();
  expect(terminal).not.toBeNull();
  expect(terminal.x).toBeGreaterThan(sidebar.x);
  expect(terminal.width).toBeGreaterThan(sidebar.width * 1.7);
  expect(Math.abs(terminal.y - sidebar.y)).toBeLessThan(4);
  await expect(page.locator("#pause")).toBeHidden();
  await expect(page.locator("#skip")).toBeHidden();
  await expect(page.locator("#replay")).toBeHidden();

  await page.locator("#speed").selectOption("fast");
  await start(page);
  await expect(page.locator("#pause")).toBeVisible();
  await expect(page.locator("#skip")).toBeVisible();
  await expect(page.locator("#replay")).toBeHidden();
  await page.locator("#skip").click();
  await expect(page.locator("#pause")).toBeHidden();
  await expect(page.locator("#skip")).toBeHidden();
  await expect(page.locator("#replay")).toBeVisible();
  await expect(page.locator("#result")).toBeVisible();
  const result = await page.locator("#result").boundingBox();
  expect(result.x).toBe(terminal.x);
  expect(result.width).toBe(terminal.width);
});
