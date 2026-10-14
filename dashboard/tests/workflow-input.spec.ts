import { test, expect } from "@playwright/test";

const workflowId = "11111111-1111-4111-8111-111111111111", versionId = "22222222-2222-4222-8222-222222222222";
const sourceId = "33333333-3333-4333-8333-333333333333", processorId = "44444444-4444-4444-8444-444444444444";
const vdId = "55555555-5555-4555-8555-555555555555", runId = "66666666-6666-4666-8666-666666666666";
const now = "2026-10-14T00:00:00Z";

test("changing sensor to computed input keeps processing and connections, including a failed catalog retry", async ({ page }, testInfo) => {
  const sensor = { kind: "SENSOR", deviceId: "temperature", property: "value" };
  const computed = { kind: "CONTINUOUS_VD", vdId, outputId: "mean" };
  const profiles = [
    { id: sourceId, key: "데이터 입력", version: "1.0.0", kind: "SERVICE", digest: "fixture", createdAt: now,
      spec: { inputs: {}, outputs: {}, stream: { source: "WORKFLOW_DATA", inputs: {}, outputs: { sample: { mediaType: "application/json", maxPayloadBytes: 4096 } } } } },
    { id: processorId, key: "값 처리", version: "1.0.0", kind: "SERVICE", digest: "fixture", createdAt: now,
      spec: { stream: { inputs: { input: { mediaType: "application/json", maxPayloadBytes: 4096 } }, outputs: {} } } },
  ];
  const original = { tasks: [{ key: "input", serviceProfileVersionId: sourceId, parameters: { dataSource: sensor, keep: "source" } },
    { key: "process", serviceProfileVersionId: processorId, parameters: { scale: 2 } }],
    dependencies: [{ fromTask: "input", toTask: "process", fromPort: "sample", toPort: "input", mode: "STREAM" }] };
  const workflow = { id: workflowId, key: "live-input", displayName: "실시간 입력 시험", createdAt: now };
  let reads = 0, published: typeof original | null = null;
  await page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "workflow-data-sources") {
      if (++reads === 1) return route.fulfill({ status: 503, json: { message: "입력 목록을 다시 확인하세요." } });
      return route.fulfill({ json: { items: [{ selection: sensor, label: "온도 원본", unit: "C", type: "float64", available: true },
        { selection: computed, label: "가상 평균 · mean", unit: "C", type: "number", available: true }], truncated: false, samplingIntervalSeconds: 1, asOf: now } });
    }
    if (path === `workflows/${workflowId}/versions`) {
      const body = request.postDataJSON(); published = { tasks: body.tasks, dependencies: body.dependencies };
      expect(body.version).toBe("1.1.0");
      return route.fulfill({ status: 201, json: { id: vdId, workflowId, version: body.version, dag: published, digest: "fixture", createdAt: now } });
    }
    let body: unknown;
    if ((path === "csrf" || path === "workflow-csrf")) body = { token: "fixture" };
    else if (path === "profiles/SERVICE") body = { items: profiles, nextOffset: null };
    else if (path === "nodes" || path === "workflow-runs") body = { items: [], nextOffset: null };
    else if (path === "workflows") body = { items: [workflow], nextOffset: null };
    else if (path === `workflows/${workflowId}`) body = { workflow, versions: [{ id: published ? vdId : versionId, workflowId,
      version: published ? "1.1.0" : "1.0.0", dag: published || original, digest: "fixture", createdAt: now }], nextOffset: null };
    else return route.fulfill({ status: 404, json: {} });
    return route.fulfill({ json: body });
  });
  await page.goto(`/workflows?workflow=${workflowId}`);
  await page.getByRole("button", { name: "1.0.0", exact: true }).click();
  await page.getByRole("button", { name: "이 버전으로 편집하기", exact: true }).click();
  await page.getByRole("combobox", { name: /^편집할 작업/ }).selectOption("input");
  await expect(page.getByLabel("입력 종류", { exact: true })).toHaveValue("SENSOR");
  await expect(page.getByRole("main").getByRole("alert")).toContainText("입력 목록을 다시 확인하세요.");
  await page.getByRole("button", { name: "입력 목록 새로고침", exact: true }).click();
  await page.getByLabel("입력 종류", { exact: true }).selectOption("CONTINUOUS_VD");
  await page.getByLabel("입력 데이터", { exact: true }).selectOption({ label: "가상 평균 · mean · C" });
  await expect(page.getByLabel("워크플로 시각 편집기")).toContainText("작업 2 · 연결 1");
  await page.getByRole("complementary", { name: "선택 작업 설정" }).screenshot({ path: testInfo.outputPath("workflow-input.png") });
  await page.getByRole("button", { name: "새 DAG 버전 발행", exact: true }).click();
  await page.getByLabel("DAG 버전", { exact: true }).fill("1.1.0");
  await page.getByRole("button", { name: "DAG 발행", exact: true }).click();
  await expect(page.getByText("DAG 버전을 발행했습니다.", { exact: true })).toBeVisible();
  expect(published).toEqual({ ...original, tasks: [{ ...original.tasks[0], parameters: { dataSource: computed, keep: "source" } }, original.tasks[1]] });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test("normal finish retries without cancelling the continuous run", async ({ page }) => {
  let posts = 0;
  const run = { id: runId, workflowVersionId: versionId, mode: "AUTO", state: "RUNNING", parameters: { workflowSourceBindings: { input: { kind: "SENSOR", deviceId: "temperature" } } }, createdAt: now, updatedAt: now };
  await page.route("**/api/control-plane/**", async route => {
    const path = new URL(route.request().url()).pathname.replace("/api/control-plane/", "");
    if (path === `workflow-runs/${runId}/drain`) {
      expect(route.request().postDataJSON()).toEqual({});
      return route.fulfill(++posts === 1 ? { status: 503, json: { message: "종료 요청을 다시 보내세요." } } : { json: { runId, state: "DRAINING" } });
    }
    if (path.endsWith("/cancel")) throw new Error("Normal finish must not invoke cancellation");
    let body: unknown;
    if ((path === "csrf" || path === "workflow-csrf")) body = { token: "fixture" };
    else if (path === `workflow-runs/${runId}`) body = { run, tasks: [] };
    else if (path.endsWith("/placements")) body = [];
    else if (path === "workflow-runs") body = { items: [run], nextOffset: null };
    else body = { items: [], nextOffset: null };
    return route.fulfill({ json: body });
  });
  await page.goto(`/runs?run=${runId}`);
  await page.getByRole("button", { name: "정상 종료", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText("종료 요청을 다시 보내세요.");
  await page.getByRole("button", { name: "정상 종료", exact: true }).click();
  await expect(page.getByText("정상 종료를 요청했습니다. 마지막 처리 결과를 저장하는 중입니다.", { exact: true })).toBeVisible();
  expect(posts).toBe(2);
});
