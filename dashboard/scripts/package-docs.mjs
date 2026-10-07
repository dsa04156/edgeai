import { readdir, lstat, mkdir, copyFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import sources from "../docs-sources.json" with { type: "json" };

const dashboard = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const root = path.dirname(dashboard);
const destination = path.join(dashboard, ".next/standalone");
let copied = 0;

async function copyDocument(source) {
  const filename = path.join(root, source);
  const stat = await lstat(filename);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 2 * 1024 * 1024) return;
  await mkdir(path.dirname(path.join(destination, source)), { recursive: true });
  await copyFile(filename, path.join(destination, source));
  copied++;
}

async function walk(relative) {
  if ((await lstat(path.join(root, relative))).isSymbolicLink()) return;
  for (const entry of await readdir(path.join(root, relative), { withFileTypes: true })) {
    if (entry.name.startsWith(".") || entry.name === "AGENTS.md" || entry.isSymbolicLink()) continue;
    const source = `${relative}/${entry.name}`;
    if (entry.isDirectory()) await walk(source);
    else if (entry.isFile() && /\.(md|ya?ml)$/i.test(entry.name)) await copyDocument(source);
  }
}

// Explicit packaging avoids tracing the entire repository (including development worktrees).
await walk("docs");
for (const source of sources.files) {
  let safe = true;
  const parts = source.split("/");
  for (let i = 1; i <= parts.length; i++) {
    try { if ((await lstat(path.join(root, ...parts.slice(0, i)))).isSymbolicLink()) safe = false; }
    catch (error) { if (error.code === "ENOENT") safe = false; else throw error; }
  }
  if (safe) await copyDocument(source);
}
console.log(`Packaged ${copied} project documents for the standalone dashboard.`);
