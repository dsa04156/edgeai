import type { NextConfig } from "next";
import path from "node:path";

const config: NextConfig = {
  // Next 16 blocks HMR from IP origins unless they are explicitly allowed.
  // Without that connection, the development client never starts hydration.
  allowedDevOrigins: ["127.0.0.1", "192.168.0.56"],
  // Polling should not flood the terminal with request summaries.
  logging: { incomingRequests: false },
  poweredByHeader: false,
  output: "standalone",
  outputFileTracingRoot: path.join(__dirname, ".."),
};
export default config;
