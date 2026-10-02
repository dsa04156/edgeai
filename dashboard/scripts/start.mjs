import { cpSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const dashboard = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const standalone = path.join(dashboard, ".next/standalone/dashboard");
if (!existsSync(path.join(standalone, "server.js"))) throw new Error("Build Dashboard before starting it");
mkdirSync(path.join(standalone, ".next"), { recursive: true });
cpSync(path.join(dashboard, ".next/static"), path.join(standalone, ".next/static"), { recursive: true });
if (existsSync(path.join(dashboard, "public")))
  cpSync(path.join(dashboard, "public"), path.join(standalone, "public"), { recursive: true });
process.env.PORT = process.env.EDGEAI_DASHBOARD_PORT || "13080";
process.env.HOSTNAME = "127.0.0.1";
await import(pathToFileURL(path.join(standalone, "server.js")).href);
