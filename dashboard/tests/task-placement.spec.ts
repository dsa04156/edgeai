import { test, expect } from "@playwright/test";

test("task placements preserve failed input, show NODE and REMOTE waiting targets, and reset for a new version", async ({ page }, testInfo) => {
  // HTTP fixtures cover UI behavior; real PostgreSQL and Kubernetes acceptance cover execution.
  const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
  const runId = "33333333-3333-4333-8333-333333333333", nodeA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", nodeB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const now = "2026-10-04T00:00:00Z", keys: string[] = []; let posts = 0;
  const taskExecutions = { root: { mode: "AUTO" }, child: { mode: "NODE", nodeId: nodeB } };
  const workflow = { id: workflowId, key: "placement", displayName: "작업 배치 시험", createdAt: now };
  const tasks = ["root", "child", "independent"].map((key, index) => ({
    id: `44444444-4444-4444-8444-44444444444${index}`, definitionId: versionId, runId, key,
    state: key === "child" ? "WAITING" : "READY", cancellationReason: null, createdAt: now, updatedAt: now,
    initialRemoteTarget: null,
    initialMode: key === "root" ? "AUTO" : "NODE", initialNodeId: key === "root" ? null : key === "child" ? nodeB : nodeA,
  }));
  const run = { id: runId, workflowVersionId: versionId, mode: "NODE", nodeId: nodeA, remoteTarget: null,
    state: "PENDING", parameters: {}, taskExecutions, retry: { maxAttempts: 1, backoffSeconds: 1, maxElapsedSeconds: 86400, retryOn: [] },
    offload: null, createdAt: now, updatedAt: now };
  const errors: string[] = []; page.on("pageerror", e => errors.push(e.message));
  await page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "workflow-runs" && request.method() === "POST") {
      keys.push(request.headers()["idempotency-key"]); posts++;
      expect(request.postDataJSON()).toEqual(posts <= 2
        ? { workflowVersionId: versionId, execution: { mode: "NODE", nodeId: nodeA }, parameters: {}, taskExecutions }
        : { workflowVersionId: versionId, execution: { mode: "AUTO" }, parameters: {}, taskExecutions: { root: { mode: "NODE", nodeId: nodeA }, child: { mode: "REMOTE", providerKey: "reference" } } });
      if (posts === 1) return route.fulfill({ status: 404, json: { code: "NODE_NOT_FOUND", message: "작업별 실행 위치에서 참조할 노드를 찾을 수 없습니다." } });
      return route.fulfill({ status: posts === 2 ? 201 : 200, json: run });
    }
    let body: unknown;
    if (path === "platform") body = { capabilities: ["workflows", "runs", "tasks", "results"] };
    else if ((path === "csrf" || path === "workflow-csrf")) body = { token: "fixture" };
    else if (path === "profiles/SERVICE") body = { items: [], nextOffset: null };
    else if (path === "nodes") body = { items: [nodeA, nodeB].map((id, i) => ({ id, name: `edge-${i ? "b" : "a"}`, architecture: "amd64", status: "READY" })), nextOffset: null };
    else if (path === "workflows") body = { items: [workflow], nextOffset: null };
    else if (path === `workflows/${workflowId}`) {
      const versions = ["1.0.0", "1.1.0"].filter(version => !url.searchParams.has("version") || url.searchParams.get("version") === version);
      body = { workflow, versions: versions.map(version => ({ id: version === "1.0.0" ? versionId : nodeB, workflowId, version, digest: "a".repeat(64), createdAt: now,
        dag: { tasks: tasks.map(t => ({ key: t.key, serviceProfileVersionId: versionId, parameters: {} })), dependencies: [] } })), nextOffset: null };
    } else if (path === "workflow-runs") body = { items: posts > 1 ? [run] : [], nextOffset: null };
    else if (path === `workflow-runs/${runId}/placements`) body = [];
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: posts <= 2 ? tasks : tasks.map(task => task.key === "root"
      ? { ...task, initialMode: "NODE", initialNodeId: nodeA }
      : task.key === "child" ? { ...task, initialMode: "REMOTE", initialNodeId: null, initialRemoteTarget: { providerKey: "reference", configurationDigest: "sha256:" + "a".repeat(64), sourceMode: "SYNTHETIC" } } : task) };
    else if (path === `tasks/${tasks[1].id}`) body = { task: tasks[1], attempts: [], telemetry: null, offloads: [] };
    else if (path === `tasks/${tasks[1].id}/results`) body = { taskId: tasks[1].id, items: [] };
    else return route.fulfill({ status: 404, json: {} });
    await route.fulfill({ status: 200, json: body });
  });
  const openVersion = async () => {
    await page.goto("/workflows");
    await page.getByRole("button", { name: "작업 배치 시험 placement", exact: true }).click();
    await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  };
  await openVersion();
  await page.getByRole("combobox", { name: "실행 위치 정책", exact: true }).selectOption("NODE");
  await page.getByRole("combobox", { name: "실행 노드", exact: true }).selectOption(nodeA);
  await page.getByRole("combobox", { name: "root 실행 위치", exact: true }).selectOption("AUTO");
  await page.getByRole("combobox", { name: "child 실행 위치", exact: true }).selectOption("NODE");
  await page.getByRole("combobox", { name: "child 실행 노드", exact: true }).selectOption(nodeB);
  await page.getByRole("group", { name: "작업별 실행 위치", exact: true }).screenshot({ path: testInfo.outputPath("task-placement-form.png") });
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("노드를 찾을 수 없습니다");
  await expect(page.getByRole("combobox", { name: "child 실행 노드", exact: true })).toHaveValue(nodeB);
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page).toHaveURL(`/runs?run=${runId}`);
  expect(keys).toHaveLength(2); expect(new Set(keys).size).toBe(1);
  const selected = page.getByRole("region", { name: "선택한 실행", exact: true });
  await expect(selected.getByRole("row").filter({ has: page.getByRole("button", { name: "child", exact: true }) })).toContainText("최초 배치 · NODE · edge-b");
  await expect(selected.getByRole("row").filter({ has: page.getByRole("button", { name: "independent", exact: true }) })).toContainText("최초 배치 · NODE · edge-a");
  await selected.getByRole("button", { name: "child", exact: true }).click();
  await expect(selected).toContainText("선행 작업을 기다리는 중이며 아직 실행 시도가 없습니다.");
  await selected.screenshot({ path: testInfo.outputPath("task-placement-waiting.png") });
  await openVersion();
  await page.getByRole("combobox", { name: "root 실행 위치", exact: true }).selectOption("NODE");
  await page.getByRole("combobox", { name: "root 실행 노드", exact: true }).selectOption(nodeA);
  await page.getByRole("combobox", { name: "child 실행 위치", exact: true }).selectOption("REMOTE");
  await expect(page.getByLabel("child Remote 제공자 key", { exact: true })).toHaveValue("reference");
  await page.getByRole("group", { name: "작업별 실행 위치", exact: true }).screenshot({ path: testInfo.outputPath("mixed-task-placement-form.png") });
  await page.getByRole("button", { name: "실행 요청 저장", exact: true }).click();
  await expect(page).toHaveURL(`/runs?run=${runId}`);
  expect(keys).toHaveLength(3); expect(keys[2]).not.toBe(keys[0]);
  await expect(selected.getByRole("row").filter({ has: page.getByRole("button", { name: "root", exact: true }) })).toContainText("최초 배치 · NODE · edge-a");
  await expect(selected.getByRole("row").filter({ has: page.getByRole("button", { name: "child", exact: true }) })).toContainText("최초 배치 · REMOTE · reference");
  await selected.screenshot({ path: testInfo.outputPath("mixed-task-placement-waiting.png") });
  await openVersion();
  await page.getByRole("combobox", { name: "root 실행 위치", exact: true }).selectOption("NODE");
  await page.getByRole("combobox", { name: "root 실행 노드", exact: true }).selectOption(nodeA);
  await page.getByRole("button", { name: "1.1.0", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "root 실행 위치", exact: true })).toHaveValue("DEFAULT");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});
