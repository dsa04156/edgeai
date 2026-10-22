import { test, expect, type Page } from "@playwright/test";
import { deflateSync } from "node:zlib";

// Every control-plane call is answered in the browser; no inference runs here, so this proves UI behavior only.
const models = [
  { id: "fasterrcnn-resnet50-fpn-v2-coco-v1", name: "Faster R-CNN ResNet-50 FPN v2 (COCO)", nodeName: "etri-ser0003-cg0ms0", deviceName: "NVIDIA GB10", available: true },
  { id: "detector-npu", name: "객체 검출", nodeName: "edge-npu-1", deviceName: "NPU 0", available: false },
];
// A valid 400x200 PNG keeps the preview's real aspect ratio.
function png(width: number, height: number) {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (bytes: Buffer) => {
    let c = 0xffffffff;
    for (const byte of bytes) c = crcTable[(c ^ byte) & 255] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const body = Buffer.concat([Buffer.from(type), data]), out = Buffer.alloc(body.length + 8);
    out.writeUInt32BE(data.length, 0);
    body.copy(out, 4);
    out.writeUInt32BE(crc(body), body.length + 4);
    return out;
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.set([8, 2, 0, 0, 0], 8);
  const rows = Buffer.alloc((width * 3 + 1) * height, 0x80);
  for (let y = 0; y < height; y++) rows[y * (width * 3 + 1)] = 0;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", header), chunk("IDAT", deflateSync(rows)), chunk("IEND", Buffer.alloc(0))]);
}
const image = { name: "line.png", mimeType: "image/png", buffer: png(400, 200) };
const result = {
  modelId: "fasterrcnn-resnet50-fpn-v2-coco-v1", nodeName: "etri-ser0003-cg0ms0", deviceName: "NVIDIA GB10", provider: "pytorch-cuda",
  imageWidth: 400, imageHeight: 200, inferenceMs: 12.4,
  detections: [{ label: "person", score: 0.913, x: 100, y: 50, width: 200, height: 100 }, { label: "car", score: 0.5, x: 0, y: 0, width: 40, height: 20 }],
};

function mock(page: Page, predict: (body: { modelId: string; imageBase64: string }) => Promise<{ status: number; json: unknown }>) {
  const requests: { modelId: string; imageBase64: string }[] = [];
  void page.route("**/api/control-plane/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname.replace("/api/control-plane/", "");
    if (path === "csrf") return route.fulfill({ json: { token: "fixture" } });
    if (path === "inference/models") return route.fulfill({ json: { items: models } });
    if (path === "inference/predict" && request.method() === "POST") {
      expect(request.headers()["x-csrf-token"]).toBe("fixture");
      const body = request.postDataJSON();
      requests.push(body);
      return route.fulfill(await predict(body));
    }
    return route.fulfill({ status: 404, json: { message: `unmocked ${path}` } });
  });
  return requests;
}
const run = (page: Page) => page.getByRole("button", { name: "추론 실행", exact: true });

test("upload, run once, and show original-coordinate boxes with a bounded result table", async ({ page }, testInfo) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  let release!: () => void;
  const held = new Promise<void>(resolve => (release = resolve));
  const requests = mock(page, async () => (await held, { status: 200, json: result }));
  await page.goto("/ui/main/inference");
  const modelField = page.locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name: "모델" }) });
  await expect(modelField).toContainText("Faster R-CNN ResNet-50 FPN v2 (COCO) · etri-ser0003-cg0ms0 / NVIDIA GB10");
  // The select may truncate the long label; node and GPU stay fully visible and inside the viewport.
  for (const text of ["실행 노드 etri-ser0003-cg0ms0", "GPU NVIDIA GB10"]) {
    const line = modelField.getByText(text, { exact: true });
    await expect(line).toBeVisible();
    const [box, size] = [await line.boundingBox(), page.viewportSize()!];
    expect(box!.x + box!.width).toBeLessThanOrEqual(size.width);
    expect(await line.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  }
  await expect(run(page)).toBeDisabled();
  // The unavailable NPU model is listed but cannot be chosen.
  await modelField.locator(".kel-select").click();
  await expect(page.getByRole("option", { name: /edge-npu-1 \/ NPU 0 · 사용 불가/ })).toHaveAttribute("aria-disabled", "true");
  await page.keyboard.press("Escape");

  await page.getByLabel("이미지 파일").setInputFiles(image);
  await expect(page.getByRole("img", { name: "line.png 미리보기" })).toBeVisible();
  await run(page).click();
  await expect(run(page)).toBeDisabled();
  await expect(page.getByRole("status").filter({ hasText: "추론 중…" })).toBeVisible();
  await run(page).click({ force: true });
  release();

  const figure = page.getByRole("figure");
  await expect(figure.getByText("person 91.3%")).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(requests[0].modelId).toBe("fasterrcnn-resnet50-fpn-v2-coco-v1");
  expect(requests[0].imageBase64).toBe(image.buffer.toString("base64"));
  // Box at x=100,y=50,w=200,h=100 of 400x200 → 25%/25%/50%/50% of the rendered preview.
  const [frame, box] = await Promise.all([figure.getByRole("img").boundingBox(), figure.locator(".inference-box").first().boundingBox()]);
  expect(Math.abs(box!.x - frame!.x - frame!.width * 0.25)).toBeLessThan(2);
  expect(Math.abs(box!.y - frame!.y - frame!.height * 0.25)).toBeLessThan(2);
  expect(Math.abs(box!.width - frame!.width * 0.5)).toBeLessThan(2);
  await expect(page.getByText("pytorch-cuda")).toBeVisible();
  await expect(page.getByText("12.4 ms")).toBeVisible();
  const table = page.locator(".inference-detections");
  await expect(table.getByRole("row")).toHaveCount(3);
  await expect(table).toContainText("91.3%");
  // The table never widens the page.
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("inference-result.png"), fullPage: true });

  // Changing the input clears the stale result.
  await page.getByLabel("이미지 파일").setInputFiles({ ...image, name: "next.png" });
  await expect(figure.getByText("person 91.3%")).toHaveCount(0);
  await expect(page.locator(".inference-detections")).toHaveCount(0);
  expect(errors).toEqual([]);
});

test("rejects unsupported files before sending and shows server errors without stale results", async ({ page }, testInfo) => {
  const requests = mock(page, async () => ({ status: 503, json: { message: "GPU 추론 서버가 준비되지 않았습니다." } }));
  await page.goto("/ui/main/inference");
  await page.getByLabel("이미지 파일").setInputFiles({ name: "a.gif", mimeType: "image/gif", buffer: Buffer.from("GIF89a") });
  await expect(page.getByRole("alert")).toContainText("PNG 또는 JPEG");
  await expect(run(page)).toBeDisabled();
  await page.getByLabel("이미지 파일").setInputFiles({ name: "big.jpg", mimeType: "image/jpeg", buffer: Buffer.alloc(4 * 1024 * 1024 + 1) });
  await expect(page.getByRole("alert")).toContainText("4 MiB");
  await expect(run(page)).toBeDisabled();
  expect(requests).toHaveLength(0);

  await page.getByLabel("이미지 파일").setInputFiles(image);
  await expect(page.getByRole("alert")).toHaveCount(0);
  await run(page).click();
  await expect(page.getByRole("alert")).toContainText("GPU 추론 서버가 준비되지 않았습니다.");
  await expect(page.locator(".inference-box")).toHaveCount(0);
  await expect(run(page)).toBeEnabled();
  await page.screenshot({ path: testInfo.outputPath("inference-error.png"), fullPage: true });
});

test("empty detections and an empty model list are explicit states", async ({ page }) => {
  mock(page, async () => ({ status: 200, json: { ...result, detections: [] } }));
  await page.goto("/ui/main/inference");
  await page.getByLabel("이미지 파일").setInputFiles(image);
  await run(page).click();
  await expect(page.getByText("검출된 객체가 없습니다.")).toBeVisible();

  await page.unroute("**/api/control-plane/**");
  await page.route("**/api/control-plane/inference/models", route => route.fulfill({ json: { items: [] } }));
  await page.getByRole("button", { name: "모델 새로고침" }).click();
  await expect(page.getByText("추론에 사용할 수 있는 모델이 없습니다.")).toBeVisible();
  await expect(run(page)).toBeDisabled();
});

test("a sole configured but unavailable model keeps its identity visible and cannot run", async ({ page }) => {
  const requests = mock(page, async () => ({ status: 200, json: result }));
  await page.route("**/api/control-plane/inference/models", route => route.fulfill({ json: { items: [{ ...models[0], available: false }] } }));
  await page.goto("/ui/main/inference");
  const modelField = page.locator(".kel-form-item").filter({ has: page.getByRole("combobox", { name: "모델" }) });
  for (const text of ["Faster R-CNN ResNet-50 FPN v2 (COCO)", "실행 노드 etri-ser0003-cg0ms0", "GPU NVIDIA GB10", "사용 불가"]) {
    const line = modelField.getByText(text, { exact: true });
    await expect(line).toBeVisible();
    const [box, size] = [await line.boundingBox(), page.viewportSize()!];
    expect(box!.x + box!.width).toBeLessThanOrEqual(size.width);
    expect(await line.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
  }
  await expect(page.getByText("추론에 사용할 수 있는 모델이 없습니다.")).toBeVisible();
  await page.getByLabel("이미지 파일").setInputFiles(image);
  await expect(run(page)).toBeDisabled();
  await run(page).click({ force: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(requests).toHaveLength(0);
});

test("a JPEG with EXIF rotation is previewed in its encoded pixel orientation, the one the boxes use", async ({ page }) => {
  mock(page, async () => ({ status: 200, json: result }));
  await page.goto("/ui/main/inference");
  const jpeg = Buffer.from(await page.evaluate(() => {
    const canvas = Object.assign(document.createElement("canvas"), { width: 400, height: 200 });
    return canvas.toDataURL("image/jpeg").split(",")[1];
  }), "base64");
  // APP1 Exif with Orientation=6 (rotate 90° when displayed) right after SOI.
  const exif = Buffer.from("ffe100224578696600004d4d002a000000080001011200030000000100060000" + "00000000", "hex");
  const rotated = Buffer.concat([jpeg.subarray(0, 2), exif, jpeg.subarray(2)]);
  await page.getByLabel("이미지 파일").setInputFiles({ name: "rotated.jpg", mimeType: "image/jpeg", buffer: rotated });
  await run(page).click();
  const figure = page.getByRole("figure");
  await expect(figure.getByText("person 91.3%")).toBeVisible();
  const frame = await figure.getByRole("img").boundingBox();
  expect(frame!.width / frame!.height).toBeCloseTo(2, 1);
});
