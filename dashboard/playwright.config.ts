import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testIgnore: "**/*.integration.spec.ts",
  workers: 1,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:13081", screenshot: "only-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["iPhone 13"], defaultBrowserType: "chromium" } },
  ],
  webServer: {
    env: { EDGEAI_WORKFLOW_ENABLED: "true", EDGEAI_API_PORT: "1", EDGEAI_API_BASE_URL: "", EDGEAI_DASHBOARD_PORT: "13081", NEXT_TELEMETRY_DISABLED: "1" },
    command: "node scripts/start.mjs",
    url: "http://127.0.0.1:13081",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
