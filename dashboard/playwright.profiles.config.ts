import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests", testMatch: "profiles.integration.spec.ts", workers: 1, reporter: "list",
  use: { baseURL: `http://127.0.0.1:${process.env.EDGEAI_DASHBOARD_PORT || "13080"}`, screenshot: "only-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["iPhone 13"], defaultBrowserType: "chromium" } },
  ],
});
