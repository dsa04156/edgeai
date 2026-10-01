import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  workers: 1,
  reporter: "list",
  use: { baseURL: "http://127.0.0.1:13081", screenshot: "only-on-failure" },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile", use: { ...devices["iPhone 13"], defaultBrowserType: "chromium" } },
  ],
  webServer: {
    env: { EDGEAI_API_PORT: "1", NEXT_TELEMETRY_DISABLED: "1" },
    command: "node node_modules/next/dist/bin/next start --hostname 127.0.0.1 --port 13081",
    url: "http://127.0.0.1:13081",
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
