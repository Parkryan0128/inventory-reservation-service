import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./src/test/browser",
  timeout: 45_000,
  workers: 1,
  reporter: [["list"], ["junit", { outputFile: "test-results/browser.xml" }]],
  use: {
    baseURL: process.env.DEMO_URL || "http://127.0.0.1:8080",
    viewport: { width: 1280, height: 900 },
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
});
