import { test, expect } from "@playwright/test";

async function connect(page, account) {
  await page.locator("#account").selectOption(account);
  await page
    .locator("#password")
    .fill(
      process.env[`${account.toUpperCase()}_PASSWORD`] ||
        `demo-${account}-password`,
    );
  await page.locator("#login-form button").click();
  await expect(page.locator("#identity")).toContainText(`${account} /`);
  await expect(page.locator("#workspace")).toBeVisible();
  await expect(page.locator("#orders tr")).not.toHaveCount(0);
  if (await page.locator("#product").inputValue()) {
    await expect(page.locator("#metadata")).not.toBeEmpty();
  }
}

async function createProduct(page) {
  const sku = `BROWSER-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  await page.locator("#sku").fill(sku);
  await page.locator("#name").fill(sku);
  await page.locator("#price").fill("750");
  await page.locator("#stock").fill("3");
  const created = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/products") &&
      response.request().method() === "POST",
  );
  await page.locator("#product-form button").click();
  const response = await created;
  expect(response.status()).toBe(201);
  const product = await response.json();
  await expect(page.locator("#products")).toContainText(sku);
  return product;
}

test("create, reserve, replay and confirm an order through the demo", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/index.html");
  await connect(page, "admin");
  const product = await createProduct(page);
  await page.locator("#disconnect").click();
  await connect(page, "alice");
  await page.locator("#product").selectOption(product.id);
  await page.locator("#quantity").fill("2");
  const reserved = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/orders") &&
      response.request().method() === "POST",
  );
  await page.locator('#reserve-form button[type="submit"]').click();
  const order = await (await reserved).json();
  expect(order.status).toBe("RESERVED");
  await expect(page.locator("#replay")).toBeEnabled();
  const replayed = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/orders") &&
      response.request().method() === "POST",
  );
  await page.locator("#replay").click();
  expect((await (await replayed).json()).id).toBe(order.id);
  await page.locator("#disconnect").click();
  await connect(page, "bob");
  await expect(page.locator("#orders .empty")).toContainText("No orders yet");
  await expect(page.locator("#orders td[title]")).toHaveCount(0);
  await expect(page.locator("#replay")).toBeDisabled();
  await page.locator("#disconnect").click();
  await connect(page, "admin");
  const row = page
    .locator("#orders tr")
    .filter({ has: page.locator(`td[title="${order.id}"]`) });
  await row.getByRole("button", { name: "Confirm payment" }).click();
  await expect(row).toContainText("CONFIRMED");
  const card = page.locator(".product-card").filter({ hasText: product.sku });
  await expect(card.locator(".count strong")).toHaveText(["1", "0", "2"]);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(errors).toEqual([]);
});

test("disconnect during a poll does not populate the next account with old orders", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/index.html");
  await connect(page, "alice");
  await expect(page.locator("#orders tr")).not.toHaveCount(0);

  let release;
  const blocked = new Promise((resolve) => {
    release = resolve;
  });
  let finished;
  const delivered = new Promise((resolve) => {
    finished = resolve;
  });
  let started;
  const intercepted = new Promise((resolve) => {
    started = resolve;
  });
  await page.route("**/api/orders", async (route) => {
    const authorization = route.request().headers().authorization;
    if (
      authorization ===
      "Basic " +
        Buffer.from(
          `alice:${process.env.ALICE_PASSWORD || "demo-alice-password"}`,
        ).toString("base64")
    ) {
      started();
      await blocked;
      await route.fulfill({
        json: [
          {
            id: "stale-alice-order",
            ownerId: "alice",
            status: "RESERVED",
            quantity: 1,
            totalPriceCents: 100,
            currency: "USD",
            expiresAt: new Date().toISOString(),
          },
        ],
      });
      finished();
    } else {
      await route.continue();
    }
  });
  await page.locator("#refresh").click();
  await intercepted;
  await page.locator("#disconnect").click();
  await expect(page.locator("#orders")).toBeEmpty();
  await connect(page, "bob");
  release();
  await delivered;
  await expect(page.locator("#orders .empty")).toContainText("No orders yet");
  await expect(page.locator("#orders td[title]")).toHaveCount(0);
  await expect(page.locator("#identity")).toContainText("bob /");
  await expect(page.locator("#replay")).toBeDisabled();
  expect(errors).toEqual([]);
});
