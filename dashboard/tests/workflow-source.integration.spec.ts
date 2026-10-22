import { test, expect, type Page } from "@playwright/test";

// Every control-plane call is answered in the browser; the console's API origins should point at unused local ports.
const now = "2026-10-22T00:00:00Z";
const workflowId = "00000000-0000-4000-8000-000000000001", sourceId = "00000000-0000-4000-8000-000000000002", processorId = "00000000-0000-4000-8000-000000000003";
const temperature = { kind: "SENSOR", deviceId: "temperature", property: "value" }, humidity = { kind: "SENSOR", deviceId: "humidity", property: "value" }, label = { kind: "SENSOR", deviceId: "plc", property: "label" };
const profiles = [
  { id: sourceId, key: "data-input", version: "1.0.0", kind: "SERVICE", digest: "fixture", createdAt: now,
    spec: { inputs: {}, outputs: {}, stream: { source: "WORKFLOW_DATA", inputs: {}, outputs: { sample: { mediaType: "application/json", maxPayloadBytes: 4096 } } } } },
  { id: processorId, key: "value-process", version: "1.0.0", kind: "SERVICE", digest: "fixture", createdAt: now,
    spec: { inputs: {}, outputs: {}, stream: { inputs: { input: { mediaType: "application/json", maxPayloadBytes: 4096 } }, outputs: {} } } },
];
const legacy = { tasks: [{ key: "input", serviceProfileVersionId: sourceId, parameters: { dataSource: temperature, window: 5 } },
  { key: "process", serviceProfileVersionId: processorId, parameters: { threshold: 3 } }],
  dependencies: [{ fromTask: "input", fromPort: "sample", toTask: "process", toPort: "input", mode: "STREAM" }] };
const workflow = { id: workflowId, key: "full-input", displayName: "전체 관측 시험", createdAt: now };

function mock(page: Page) {
  const versions: { id: string; workflowId: string; version: string; dag: unknown; digest: string; createdAt: string }[] =
    [{ id: "00000000-0000-4000-8000-000000000010", workflowId, version: "1.0.0", dag: legacy, digest: "fixture", createdAt: now }];
  const published: unknown[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname.replace("/api/control-plane/", "");
    if (path === "workflow-data-sources") return route.fulfill({ json: { asOf: now, samplingIntervalSeconds: 1, truncated: false, items: [
      { selection: temperature, label: "온도 원본", unit: "C", type: "float64", available: true },
      { selection: humidity, label: "습도 원본", unit: "%", type: "float64", available: true },
      { selection: label, label: "PLC 상태 문자열", unit: "", type: "string", available: true }] } });
    if (path === "csrf" || path === "workflow-csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "profiles/SERVICE") return route.fulfill({ json: { items: profiles, nextOffset: null } });
    if (path === "nodes") return route.fulfill({ json: { items: [], nextOffset: null } });
    if (path === "workflows") return route.fulfill({ json: { items: [workflow], nextOffset: null } });
    if (path === `workflows/${workflowId}/versions` && request.method() === "POST") {
      const { version, ...dag } = request.postDataJSON();
      published.push(dag);
      const saved = { id: `00000000-0000-4000-8000-0000000000${10 + versions.length}`, workflowId, version, dag, digest: "fixture", createdAt: now };
      versions.unshift(saved);
      return route.fulfill({ status: 201, json: saved });
    }
    if (path === `workflows/${workflowId}`) {
      const wanted = url.searchParams.get("version");
      return route.fulfill({ json: { workflow, versions: wanted ? versions.filter(v => v.version === wanted) : versions, nextOffset: null } });
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return published;
}
const drawer = (page: Page) => page.getByRole("dialog", { name: "작업 설정" });
const field = (page: Page, name: string) => drawer(page).locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name, exact: true }) });
async function pick(page: Page, name: string, option: string) {
  await field(page, name).locator(".kel-select").click();
  await page.getByRole("option", { name: option, exact: true }).click();
}
async function openInputTask(page: Page, version: string) {
  await page.getByRole("button", { name: version, exact: true }).click();
  await expect(page.getByRole("heading", { name: `선택한 DAG · ${version}` })).toBeVisible();
  await page.getByRole("button", { name: "이 버전으로 편집", exact: true }).click();
  await page.getByRole("region", { name: "input", exact: true }).locator(".canvas-node-title").click();
  await expect(field(page, "전달 방식")).toBeVisible();
}
const parametersJson = async (page: Page) => JSON.parse(await drawer(page).getByRole("textbox", { name: "매개변수 JSON" }).inputValue());

test("WORKFLOW_DATA delivery is selected explicitly, survives source edits and publish, and LATEST stays the omitted default", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  const published = mock(page);
  await page.goto(`/ui/main/workflows?workflow=${workflowId}`);
  await openInputTask(page, "1.0.0");
  // A legacy version without delivery reads as LATEST.
  await expect(field(page, "전달 방식")).toContainText("최신값");
  await expect(drawer(page)).toContainText("1초마다 최신 유효값을 처리합니다.");

  await pick(page, "전달 방식", "모든 관측값");
  await expect(drawer(page)).toContainText("실행 시작 뒤 새로 수락된 관측을 수락 순서대로 한 번씩 처리합니다. 시작 전 관측은 전달하지 않으며(이전 이력 보충 없음), 수집 전 손실까지 보장하지는 않습니다.");
  await pick(page, "입력 데이터", "습도 원본 · %");
  expect(await parametersJson(page)).toEqual({ dataSource: { ...humidity, delivery: "ALL_OBSERVATIONS" }, window: 5 });

  // A string measurement cannot use ALL_OBSERVATIONS; the drawer says so before the server would reject the run.
  await pick(page, "입력 데이터", "PLC 상태 문자열");
  await expect(page.getByRole("alert").filter({ hasText: "문자열 측정 항목은 모든 관측값 전달을 지원하지 않습니다." })).toBeVisible();
  await drawer(page).screenshot({ path: testInfo.outputPath("unsupported-string.png") });
  await pick(page, "입력 데이터", "습도 원본 · %");
  await drawer(page).screenshot({ path: testInfo.outputPath("all-observations.png") });
  await page.getByRole("button", { name: "작업 반영", exact: true }).click();

  await page.getByLabel("새 DAG 버전").fill("1.1.0");
  await page.getByRole("button", { name: "DAG 버전 발행", exact: true }).click();
  await expect(page.getByText("DAG 버전을 발행했습니다.")).toBeVisible();
  const allObservations = { ...legacy, tasks: [{ ...legacy.tasks[0], parameters: { dataSource: { ...humidity, delivery: "ALL_OBSERVATIONS" }, window: 5 } }, legacy.tasks[1]] };
  expect(published).toEqual([allObservations]);

  // Reloading the saved version shows the stored choice; switching back to LATEST removes the policy.
  await page.reload();
  await openInputTask(page, "1.1.0");
  await expect(field(page, "전달 방식")).toContainText("모든 관측값");
  await pick(page, "입력 종류", "가상 장치 계산 결과");
  expect(await parametersJson(page)).toEqual({ dataSource: { kind: "CONTINUOUS_VD", delivery: "ALL_OBSERVATIONS" }, window: 5 });
  await pick(page, "입력 종류", "센서 값");
  await pick(page, "입력 데이터", "습도 원본 · %");
  await pick(page, "전달 방식", "최신값");
  expect(await parametersJson(page)).toEqual({ dataSource: humidity, window: 5 });
  await page.getByRole("button", { name: "작업 반영", exact: true }).click();
  await page.getByLabel("새 DAG 버전").fill("1.2.0");
  await page.getByRole("button", { name: "DAG 버전 발행", exact: true }).click();
  await expect(page.getByText("DAG 버전을 발행했습니다.")).toBeVisible();
  expect(published.at(-1)).toEqual({ ...legacy, tasks: [{ ...legacy.tasks[0], parameters: { dataSource: humidity, window: 5 } }, legacy.tasks[1]] });
  expect(errors).toEqual([]);
});
