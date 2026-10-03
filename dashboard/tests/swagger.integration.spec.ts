import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";

test.use({ httpCredentials: { username: process.env.EDGEAI_API_USER || "", password: process.env.EDGEAI_API_PASSWORD || "" } });

test("Swagger renders the exact contract and publishes with automatic CSRF", async ({ page }, testInfo) => {
  if (!process.env.EDGEAI_API_USER || !process.env.EDGEAI_API_PASSWORD) throw new Error("Development credentials required");
  const api = `http://127.0.0.1:${process.env.EDGEAI_API_PORT || "18080"}`;
  const errors: string[] = [];
  const externalRequests: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  page.on("request", request => {
    if (request.url().startsWith("http") && new URL(request.url()).origin !== api) externalRequests.push(new URL(request.url()).origin);
  });
  await page.goto(`${api}/swagger-ui.html`);
  await expect(page.getByRole("heading", { name: /EdgeAI Control Plane/ })).toBeVisible();
  const contract = await page.request.get(`${api}/openapi.yaml`);
  expect(await contract.text()).toBe(await readFile("../contracts/openapi/platform-api.yaml", "utf8"));
  await expect(page.locator(".opblock")).toHaveCount(41);
  await expect(page.locator("#operations-Device-registerDevice .opblock-summary-description")).toHaveText("물리 장치 등록");
  await expect(page.locator("#operations-Device-reportDeviceObservation .opblock-summary-description")).toHaveText("장치 상태·작은 관측 데이터 보고");
  await expect(page.locator("#operations-Device-issueDeviceStreamToken .opblock-summary-description")).toHaveText("현재 장치 세션의 스트림 배정 토큰 발급");
  await expect(page.locator("#operations-Node-listNodes .opblock-summary-description")).toHaveText("관측된 Kubernetes 실행 노드 목록");
  await expect(page.locator("#operations-Workflow-publishWorkflowVersion .opblock-summary-description")).toHaveText("검증된 DAG 버전 발행");
  await expect(page.locator("#operations-Run-createWorkflowRun .opblock-summary-description")).toHaveText("워크플로 실행 요청 생성");
  const runOperation = page.locator("#operations-Run-createWorkflowRun");
  await runOperation.locator(".opblock-summary-control").click();
  const runDescription = runOperation.locator(".opblock-description-wrapper").filter({ hasText: "발행된 DAG를 Run과 Task로 구체화합니다." });
  await expect(runDescription).toContainText("initialMode/initialNodeId/initialVdId/initialRemoteTarget");
  await expect(runDescription).toContainText("BATCH의 AUTO/NODE/VD/REMOTE 최초 배치");
  await expect(runDescription).toContainText("STREAM의 VD/REMOTE 배치는409");
  await expect(runDescription).toContainText("재시도는 직전 Attempt의 실제 위치를 계승");
  await runOperation.screenshot({ path: testInfo.outputPath("task-placement-swagger.png") });
  await runOperation.locator(".opblock-summary-control").click();
  await expect(page.locator("#operations-Run-listRunStreamRoutes .opblock-summary-description")).toHaveText("실행의 스트림 입력·그룹·경로 세대 조회");
  await expect(page.locator("#operations-Result-getTaskResults .opblock-summary-description")).toHaveText("작업의 검증된 결과와 artifact 메타데이터 조회");
  await expect(page.locator("#operations-Task-offloadTask .opblock-summary-description")).toHaveText("실행 중인 작업을 다른 노드 또는 Remote로 전환");
  await expect(page.locator("#operations-Operation-getOperation .opblock-summary-description")).toHaveText("비동기 실행 전환·VD 작업 상태 조회");
  await page.screenshot({ path: testInfo.outputPath("swagger-ui.png"), fullPage: true });

  await expect(page.locator("#operations-VD-createVirtualDevice .opblock-summary-description")).toHaveText("VD 식별자와 원본 장치 연결 등록");
  await expect(page.locator("#operations-VD-provisionVirtualDevice .opblock-summary-description")).toHaveText("VD 실행 시작 요청");
  await expect(page.locator("#operations-VD-getVirtualDeviceExecution .opblock-summary-description")).toHaveText("VD 실행 준비 상태·세대·작업 이력 조회");
  const operation = page.locator("#operations-Profile-publishProfile");
  await expect(operation.locator(".opblock-summary-description")).toHaveText("프로필 새 버전 등록");
  await operation.locator(".opblock-summary-control").click();
  await expect(operation.locator(".opblock-description-wrapper").filter({ hasText: "실제 장치/런타임 호환성 검증은 후속 개발 범위" })).toBeVisible();
  await operation.getByRole("button", { name: "Try it out" }).click();
  await operation.locator(".parameters select").selectOption("DEVICE");
  const key = `swagger-${randomUUID()}`;
  await operation.locator("textarea.body-param__text").fill(JSON.stringify({ key, version: "1.0.0", spec: { protocol: "mqtt" } }));
  const published = page.waitForResponse(response => response.request().method() === "POST" && response.url() === `${api}/api/v1/profiles/DEVICE`);
  await operation.getByRole("button", { name: "Execute", exact: true }).click();
  const response = await published;
  expect(response.status()).toBe(201);
  expect(response.request().headers()["x-csrf-token"]).toBeTruthy();
  expect((await response.json()).key).toBe(key);
  expect((await page.request.get(`${api}/api/v1/profiles/DEVICE/${key}/versions/1.0.0`)).status()).toBe(200);
  await page.goto(`${api}/swagger-ui/index.html?contract=streams`);
  await expect(page.locator(".opblock")).toHaveCount(13);
  expect(await (await page.request.get(`${api}/stream-openapi.yaml`)).text()).toBe(await readFile("../contracts/openapi/stream-api.yaml", "utf8"));
  const checkpoints = [
    ["discoverDeviceStreamRoutes", "현재 Device 세션에 고정된 Run 경로·세대 조회", "MQTT 자격 증명이나 lease"],
    ["finalizedStreamCheckpoint", "완료 허가된 현재 Runner의 최종 상태 다운로드 권한 조회", "영속 공동 완료 허가"],
    ["assignStreamExecution", "현재 Runner의 스트림 실행 포트와 복원 방식 조회", "경로 구성을 고정"],
    ["completeRunnerStream", "Runner의 최종 체크포인트를 보고하고 공동 완료 허가 조회", "마지막 처리 확인"],
    ["completeDeviceStream", "Device의 마지막 처리 확인을 보고하고 공동 완료 허가 조회", "동일 현재 Device 세션"],
    ["uploadStreamCheckpoint", "현재 Runner 체크포인트의 S3 업로드 권한 요청", "PUT 성공만으로"],
    ["commitStreamCheckpoint", "S3 파일을 검증하고 체크포인트를 원자적으로 확정", "입력·출력 위치"],
    ["latestStreamCheckpoint", "현재 Task의 최신 확정 체크포인트와 다운로드 권한 조회", "고정 S3 version"],
    ["handoverStreamCheckpoint", "이전 체크포인트를 현재 Attempt와 경로 세대로 인계", "입력 순번"],
  ];
  for (const [id, summary, description] of checkpoints) {
    const op = page.locator(`#operations-default-${id}`);
    await expect(op.locator(".opblock-summary-description")).toHaveText(summary);
    await op.locator(".opblock-summary-control").click();
    await expect(op.locator(".opblock-description-wrapper").filter({ hasText: description })).toBeVisible();
    await expect(op.locator(".response-col_status").filter({ hasText: /^409$/ })).toBeVisible();
    await op.locator(".opblock-summary-control").click();
  }
  await page.screenshot({ path: testInfo.outputPath("stream-checkpoint-swagger.png"), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length])).toEqual([0, 0]);
  expect(errors).toEqual([]);
  expect(externalRequests).toEqual([]);
});
