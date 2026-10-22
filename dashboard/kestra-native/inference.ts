export const MAX_IMAGE_BYTES = 4 * 1024 * 1024;

export function imageProblem(file: { type: string; size: number }) {
  if (!["image/png", "image/jpeg"].includes(file.type))
    return "PNG 또는 JPEG 이미지를 선택하세요.";
  if (file.size === 0) return "비어 있는 파일입니다.";
  if (file.size > MAX_IMAGE_BYTES) return "이미지는 4 MiB 이하로 선택하세요.";
  return "";
}

/** The API takes plain binary base64, not a data URL. */
export function toBase64(bytes: Uint8Array) {
  let binary = "";
  // Chunked so large images do not exceed the argument limit of fromCharCode.
  for (let offset = 0; offset < bytes.length; offset += 0x8000)
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  return btoa(binary);
}

const clip = (value: number, total: number) =>
  Math.min(Math.max(value, 0), total);
const percent = (value: number, total: number) =>
  `${Math.round((value / total) * 10000) / 100}%`;

/** Detections are original-image pixels; percentages keep them aligned at any preview size. */
export function boxStyle(
  box: { x: number; y: number; width: number; height: number },
  imageWidth: number,
  imageHeight: number,
) {
  const left = clip(box.x, imageWidth),
    top = clip(box.y, imageHeight);
  return {
    left: percent(left, imageWidth),
    top: percent(top, imageHeight),
    width: percent(clip(box.x + box.width, imageWidth) - left, imageWidth),
    height: percent(clip(box.y + box.height, imageHeight) - top, imageHeight),
  };
}
