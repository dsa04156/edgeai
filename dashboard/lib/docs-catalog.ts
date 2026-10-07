import { readdir, readFile, lstat } from "node:fs/promises";
import path from "node:path";
import sources from "../docs-sources.json";
import navigation from "../docs-navigation.json";

export const docGroups = navigation.groups;
export const primaryGroups = new Set(["getting-started", "concepts", "guides", "operations", "reference", "contributing"]);

export type DocEntry = { source: string; href: string; title: string; group: string; content: string; modified: string; description: string; primary: boolean; order: number };
const extraDocuments = sources.files;

export function documentHref(source: string): string {
  const slug = source.startsWith("docs/") ? source.slice(5) : `project/${source}`;
  return `/docs/${slug.replace(/\.md$/i, "").split("/").map(encodeURIComponent).join("/")}`;
}

// The dashboard and standalone server both run one level below the repository content.
export async function loadDocuments(root = path.resolve(process.cwd(), "..")): Promise<DocEntry[]> {
  const sources: string[] = [];
  async function walk(relative: string) {
    const directory = path.join(root, relative);
    if ((await lstat(directory)).isSymbolicLink()) return;
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (entry.name.startsWith(".") || entry.name === "AGENTS.md" || entry.isSymbolicLink()) continue;
      const file = `${relative}/${entry.name}`;
      if (entry.isDirectory()) await walk(file);
      else if (entry.isFile() && /\.(md|ya?ml)$/i.test(entry.name)) sources.push(file);
    }
  }
  await walk("docs");
  for (const source of extraDocuments) {
    // Every component must be a real file/directory, never a link outside the docs set.
    let safe = true;
    const parts = source.split("/");
    for (let i = 1; i <= parts.length; i++) {
      try { if ((await lstat(path.join(root, ...parts.slice(0, i)))).isSymbolicLink()) safe = false; }
      catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") safe = false; else throw error; }
    }
    if (safe) sources.push(source);
  }
  const entries = await Promise.all(sources.map(async source => {
    const filename = path.join(root, source);
    const stat = await lstat(filename);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 2 * 1024 * 1024) return null;
    const content = await readFile(filename, "utf8");
    const title = content.match(/^#\s+(.+?)\s*#*\s*$/m)?.[1]?.replace(/[`*_]/g, "") || path.basename(source);
    const part = source.split("/")[1];
    const order = navigation.pages.findIndex(page => page.source === source);
    const page = navigation.pages[order];
    const group = page?.group || (["adr", "evidence", "history", "requirements"].includes(part) && source.startsWith("docs/") ? part : "engineering");
    return { source, href: documentHref(source), title, group, content, modified: stat.mtime.toISOString(), description: page?.description || "", primary: order >= 0, order: order >= 0 ? order : 10000 };
  }));
  return entries.filter((entry): entry is DocEntry => entry !== null).sort((a, b) => a.order - b.order || a.title.localeCompare(b.title, "ko"));
}

export function searchDocuments(docs: DocEntry[], query: string, group = "", includeRecords = false) {
  const words = query.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean);
  return docs.filter(doc => (includeRecords || doc.primary || (group && !primaryGroups.has(group))) && (!group || doc.group === group) && words.every(word => `${doc.title}\n${doc.source}\n${doc.content}`.toLocaleLowerCase().includes(word)));
}

export function documentExcerpt(doc: DocEntry, query: string) {
  const plain = doc.content.replace(/[#`*|>]/g, "").replace(/\s+/g, " ").trim();
  const word = query.trim().split(/\s+/)[0]?.toLocaleLowerCase();
  const start = word ? Math.max(0, plain.toLocaleLowerCase().indexOf(word) - 45) : 0;
  return `${start ? "…" : ""}${plain.slice(start, start + 180)}${plain.length > start + 180 ? "…" : ""}`;
}

// Resolve only catalogued document links. Arbitrary repository files are never served.
export function resolveDocumentLink(href: string, source: string, docs: Pick<DocEntry, "source" | "href">[]): string | undefined {
  if (/^https?:\/\//i.test(href) || /^mailto:/i.test(href)) return href;
  if (href.startsWith("#")) return href;
  if (/^[a-z][a-z0-9+.-]*:/i.test(href) || href.startsWith("//") || href.includes("\\")) return undefined;
  const [file, fragment] = href.split("#", 2);
  let decoded: string;
  try { decoded = decodeURIComponent(file.split("?", 1)[0]); } catch { return undefined; }
  if (decoded.includes("\\") || decoded.includes("\0")) return undefined;
  const resolved = path.posix.normalize(decoded.startsWith("/") ? decoded.slice(1) : path.posix.join(path.posix.dirname(source), decoded));
  const target = docs.find(doc => doc.source === resolved || doc.source === `${resolved.replace(/\/$/, "")}/README.md`);
  if (target) return `${target.href}${fragment ? `#${fragment}` : ""}`;
  const directory = resolved.replace(/^docs\//, "").replace(/\/$/, "");
  const group = ({ architecture: "concepts", testing: "contributing" } as Record<string, string>)[directory] || directory;
  if (resolved.startsWith("docs/") && docGroups.some(([key]) => key === group)) return `/docs?group=${group}#documents`;
  return undefined;
}
