import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile, symlink, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { loadDocuments, resolveDocumentLink, searchDocuments, documentHref } from "../lib/docs-catalog";

test("catalogue reads approved documents, picks up edits, and excludes secrets and symlinks", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "edgeai-docs-"));
  try {
    await mkdir(path.join(root, "docs/guides"), { recursive: true });
    await mkdir(path.join(root, "private"));
    await writeFile(path.join(root, "private/secret.md"), "SECRET");
    await writeFile(path.join(root, "docs/.env"), "SECRET");
    await writeFile(path.join(root, "docs/.hidden.md"), "SECRET");
    await writeFile(path.join(root, "docs/AGENTS.md"), "instructions");
    await writeFile(path.join(root, "docs/guides/sensor-controls-and-vd-deletion.md"), "# 센서 측정\n\nEdgeX의 온도 값");
    await writeFile(path.join(root, "PROGRESS.md"), "# 현재 상태");
    await symlink(path.join(root, "private/secret.md"), path.join(root, "docs/leak.md"));
    await symlink(path.join(root, "private"), path.join(root, "docs/linked"));
    await symlink(path.join(root, "private/secret.md"), path.join(root, "README.md"));
    const docs = await loadDocuments(root);
    assert.deepEqual(docs.map(doc => doc.source).sort(), ["PROGRESS.md", "docs/guides/sensor-controls-and-vd-deletion.md"]);
    assert.equal(searchDocuments(docs, "edgex 온도", "guides").length, 1);
    assert.equal(searchDocuments(docs, "edgex", "architecture").length, 0);
    assert.equal(searchDocuments(docs, "없는단어").length, 0);
    assert.equal(searchDocuments(docs, "현재 상태").length, 0);
    assert.equal(searchDocuments(docs, "현재 상태", "", true).length, 1);
    assert.equal(searchDocuments(docs, "현재 상태", "engineering").length, 1);
    await writeFile(path.join(root, "docs/guides/sensor-controls-and-vd-deletion.md"), "# 갱신된 센서\n새 내용");
    assert.equal((await loadDocuments(root)).find(doc => doc.group === "guides")?.title, "갱신된 센서");
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("Markdown links stay inside the catalogue and preserve document anchors", () => {
  const docs = ["docs/README.md", "docs/guides/sensor.md", "PROGRESS.md"].map(source => ({ source, href: documentHref(source) }));
  assert.equal(resolveDocumentLink("../../PROGRESS.md#현재-상태", "docs/guides/sensor.md", docs), "/docs/project/PROGRESS#현재-상태");
  assert.equal(resolveDocumentLink("../README.md", "docs/guides/sensor.md", docs), "/docs/README");
  assert.equal(resolveDocumentLink("guides/", "docs/README.md", docs), "/docs?group=guides#documents");
  assert.equal(resolveDocumentLink("#명령", "docs/guides/sensor.md", docs), "#명령");
  for (const href of ["../../.env", "../../private/secret.md", "javascript:alert(1)", "data:text/html,hi", "//evil.test", "..\\secret.md", "%00.md", "%zz"]) {
    assert.equal(resolveDocumentLink(href, "docs/README.md", docs), undefined, href);
  }
  assert.equal(resolveDocumentLink("https://example.com/guide", "docs/README.md", docs), "https://example.com/guide");
});

test("missing document source is an error, not an empty success", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "edgeai-docs-missing-"));
  try { await assert.rejects(loadDocuments(root), { code: "ENOENT" }); }
  finally { await rm(root, { recursive: true, force: true }); }
});
