import { test, expect } from "@playwright/test";
import { controlPlaneOrigin } from "../lib/control-plane";

test("uses the configured internal service independently of the local development port", () => {
  const before = process.env.EDGEAI_API_BASE_URL;
  try {
    process.env.EDGEAI_API_BASE_URL = "http://edgeai-api:18080/";
    expect(controlPlaneOrigin()).toBe("http://edgeai-api:18080");
    process.env.EDGEAI_API_BASE_URL = "https://api.example.test";
    expect(controlPlaneOrigin()).toBe("https://api.example.test");
  } finally {
    if (before === undefined) delete process.env.EDGEAI_API_BASE_URL;
    else process.env.EDGEAI_API_BASE_URL = before;
  }
});

test("rejects credentials and paths in upstream configuration", () => {
  const before = process.env.EDGEAI_API_BASE_URL;
  try {
    for (const invalid of ["file:///tmp/private", "http://user:password@api", "http://api/path", "http://api?url=x", "http://api#fragment"]) {
      process.env.EDGEAI_API_BASE_URL = invalid;
      expect(() => controlPlaneOrigin()).toThrow();
    }
  } finally {
    if (before === undefined) delete process.env.EDGEAI_API_BASE_URL;
    else process.env.EDGEAI_API_BASE_URL = before;
  }
});
