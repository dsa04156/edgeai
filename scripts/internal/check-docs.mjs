import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const require = createRequire(path.join(root, "dashboard/package.json"));
const { unified } = await import(require.resolve("unified"));
const { default: remarkParse } = await import(require.resolve("remark-parse"));
const { default: remarkGfm } = await import(require.resolve("remark-gfm"));
const { default: Slugger } = await import(require.resolve("github-slugger"));
const { toString } = await import(require.resolve("mdast-util-to-string"));
const yaml = require("js-yaml");
const navigation = JSON.parse(fs.readFileSync(path.join(root, "dashboard/docs-navigation.json"), "utf8"));
const schema = yaml.load(fs.readFileSync(path.join(root, "contracts/openapi/platform-api.yaml"), "utf8"));
const methods = new Set(["get", "post", "put", "patch", "delete"]);
const operations = Object.entries(schema.paths).flatMap(([route, item]) => Object.entries(item)
  .filter(([method]) => methods.has(method)).map(([method, operation]) => [method.toUpperCase(), route, operation.operationId]));
const apiIndex = `계약에 정의된 operation은 **${operations.length}개**입니다.\n\n| 메서드 | 경로 | operationId |\n|---|---|---|\n${operations.map(([method, route, id]) => `| \`${method}\` | \`${route}\` | \`${id}\` |`).join("\n")}\n`;
const apiFile = path.join(root, "docs/reference/api.md");
const marker = /(?<=<!-- BEGIN GENERATED OPERATIONS -->\n)[\s\S]*?(?=<!-- END GENERATED OPERATIONS -->)/;
if (process.argv.includes("--write-api-index")) {
  const content = fs.readFileSync(apiFile, "utf8");
  if (!marker.test(content)) throw new Error("API index markers missing");
  fs.writeFileSync(apiFile, content.replace(marker, apiIndex));
}
const errors = [];
if (fs.readFileSync(apiFile, "utf8").match(marker)?.[0] !== apiIndex)
  errors.push("API index differs: node scripts/internal/check-docs.mjs --write-api-index");

const files = [];
function walk(directory) {
  for (const entry of fs.readdirSync(path.join(root, directory), { withFileTypes: true })) {
    if (entry.isSymbolicLink() || entry.name.startsWith(".") || ["adr", "evidence", "history"].includes(entry.name)) continue;
    const filename = `${directory}/${entry.name}`;
    if (entry.isDirectory()) walk(filename);
    else if (entry.name.endsWith(".md") && entry.name !== "AGENTS.md") files.push(filename);
  }
}
walk("docs");
files.push("README.md");
const parse = content => unified().use(remarkParse).use(remarkGfm).parse(content);
const trees = new Map();
function treeFor(source) {
  if (!trees.has(source)) trees.set(source, parse(fs.readFileSync(path.join(root, source), "utf8")));
  return trees.get(source);
}
function visit(node, callback) { callback(node); node.children?.forEach(child => visit(child, callback)); }
const anchors = new Map();
function anchorsFor(source) {
  if (!anchors.has(source)) {
    const ids = new Set(); const slugger = new Slugger();
    visit(treeFor(source), node => { if (node.type === "heading") ids.add(slugger.slug(toString(node))); });
    anchors.set(source, ids);
  }
  return anchors.get(source);
}
const groupKeys = new Set(navigation.groups.map(([key]) => key));
const primary = new Set();
for (const page of navigation.pages) {
  if (primary.has(page.source)) errors.push(`Duplicate navigation: ${page.source}`);
  primary.add(page.source);
  if (!fs.existsSync(path.join(root, page.source))) errors.push(`Missing navigation source: ${page.source}`);
  if (!groupKeys.has(page.group) || !page.description) errors.push(`Invalid navigation: ${page.source}`);
}
let links = 0, shellBlocks = 0;
for (const source of files) {
  const tree = treeFor(source);
  const h1 = tree.children.filter(node => node.type === "heading" && node.depth === 1);
  if (h1.length !== 1) errors.push(`${source}: expected one H1, got ${h1.length}`);
  visit(tree, node => {
    if (["link", "definition", "image"].includes(node.type)) {
      const href = node.url;
      if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith("//")) return;
      links++;
      const [url, fragment] = href.split("#");
      let decoded;
      try { decoded = decodeURIComponent(url.split("?")[0]); } catch { errors.push(`${source}: invalid URL ${href}`); return; }
      const target = decoded ? path.posix.normalize(path.posix.join(path.posix.dirname(source), decoded)) : source;
      if (target.startsWith("..") || !fs.existsSync(path.join(root, target))) { errors.push(`${source}: missing ${href}`); return; }
      // Archived fragments keep their original meaning; current target headings must match exactly.
      if (fragment && target.endsWith(".md") && !/docs\/(adr|evidence|history)\//.test(target)) {
        if (!anchorsFor(target).has(decodeURIComponent(fragment))) errors.push(`${source}: missing anchor ${href}`);
      }
    }
    if (node.type === "code" && ["bash", "sh", "shell"].includes(node.lang) && primary.has(source)) {
      shellBlocks++;
      const example = node.value.replace(/<[^>\n]+>/g, "EXAMPLE");
      const result = spawnSync("bash", ["-n"], { input: example, encoding: "utf8" });
      if (result.status !== 0) errors.push(`${source}: shell syntax at line ${node.position.start.line}: ${result.stderr.trim()}`);
    }
  });
}
if (errors.length) { console.error(errors.join("\n")); process.exitCode = 1; }
else console.log(JSON.stringify({ status: "PASS", maintainedDocuments: files.length, primaryDocuments: primary.size, localLinks: links, shellBlocks, managementOperations: operations.length }));
