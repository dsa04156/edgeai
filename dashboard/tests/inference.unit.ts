import assert from "node:assert/strict";
import { test } from "node:test";
import { boxStyle, imageProblem, MAX_IMAGE_BYTES, toBase64 } from "../kestra-native/inference.ts";

test("only non-empty PNG/JPEG files up to 4 MiB are accepted", () => {
  assert.equal(MAX_IMAGE_BYTES, 4 * 1024 * 1024);
  assert.equal(imageProblem({ type: "image/png", size: 10 }), "");
  assert.equal(imageProblem({ type: "image/jpeg", size: MAX_IMAGE_BYTES }), "");
  assert.match(imageProblem({ type: "image/jpeg", size: MAX_IMAGE_BYTES + 1 }), /4 MiB/);
  assert.match(imageProblem({ type: "image/gif", size: 10 }), /PNG|JPEG/);
  assert.match(imageProblem({ type: "image/png", size: 0 }), /비어/);
});

test("base64 is plain binary base64 without a data URL prefix, also for large inputs", () => {
  assert.equal(toBase64(new Uint8Array([0x89, 0x50, 0x4e, 0x47])), "iVBORw==");
  const large = new Uint8Array(200_000).map((_, index) => index % 251);
  assert.equal(toBase64(large), Buffer.from(large).toString("base64"));
});

test("boxes are placed in original-image pixel space and clipped to the image", () => {
  assert.deepEqual(boxStyle({ x: 100, y: 50, width: 200, height: 100 }, 400, 200), {
    left: "25%",
    top: "25%",
    width: "50%",
    height: "50%",
  });
  assert.deepEqual(boxStyle({ x: -40, y: 150, width: 100, height: 100 }, 400, 200), {
    left: "0%",
    top: "75%",
    width: "15%",
    height: "25%",
  });
});
