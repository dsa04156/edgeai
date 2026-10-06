// Server-side configuration only; never derive the upstream from a client header or URL.
export function controlPlaneOrigin(): string {
  const configured = process.env.EDGEAI_API_BASE_URL;
  if (configured) {
    const url = new URL(configured);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password ||
        url.pathname !== "/" || url.search || url.hash) {
      throw new Error("EDGEAI_API_BASE_URL must be an HTTP(S) origin without credentials or a path");
    }
    return url.origin;
  }
  const port = process.env.EDGEAI_API_PORT || "18080";
  if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535)
    throw new Error("Invalid EDGEAI_API_PORT");
  return `http://127.0.0.1:${port}`;
}

export function controlPlaneAuthorization(): string | null {
  const password = process.env.EDGEAI_API_PASSWORD;
  if (!password) return null;
  const username = process.env.EDGEAI_API_USER || "edgeai";
  return `Basic ${Buffer.from(`${username}:${password}`, "utf8").toString("base64")}`;
}
