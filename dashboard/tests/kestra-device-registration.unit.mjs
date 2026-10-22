import assert from "node:assert/strict";
import {createServer, request as httpRequest} from "node:http";
import {test} from "node:test";
import {createDeviceRegistrationMiddleware} from "../kestra-native/device-registration.ts";

async function listen(server) {
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

async function withServers(action) {
  const calls = [];
  const device = {id: "sensor-a", state: "OFFLINE", profile: {protocol: "serial-json", protocolConfig: {deviceId: "board-a", devicePath: "/dev/edgeai/board-a", baudRate: 115200}, properties: [
    {name: "temperature", dataType: "int32", sensor: "temperature", jsonPath: "raw", scale: 1, offset: 0, unit: "raw"},
    {name: "light", dataType: "int32", sensor: "light", jsonPath: "value", scale: 1, offset: 0, unit: "raw"},
  ]}};
  const backend = createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk;
    calls.push({method: req.method, path: req.url, headers: req.headers, body});
    res.setHeader("Content-Type", "application/json");
    if (req.url === "/api/v1/csrf") {
      res.setHeader("Set-Cookie", "EDGEAI_SESSION=fixture-session; Path=/; HttpOnly; SameSite=Lax");
      return res.end(JSON.stringify({token: "fixture-token"}));
    }
    if (["POST", "PUT"].includes(req.method) && req.headers["x-csrf-token"] !== "fixture-token") {
      res.statusCode = 403;return res.end(JSON.stringify({message: "CSRF rejected"}));
    }
    if (body && JSON.parse(body).name === "conflict") {
      res.statusCode = 409;return res.end(JSON.stringify({message: "같은 이름의 장치가 등록되어 있습니다."}));
    }
    if (req.url === "/api/devices/sensor-a") return res.end(JSON.stringify(device));
    if (req.method === "PUT" && req.url === "/api/devices/sensor-a/properties/temperature") {
      device.profile.properties[0] = JSON.parse(body);
      return res.end(JSON.stringify(device));
    }
    res.statusCode = req.method === "POST" ? 201 : 200;
    res.end(JSON.stringify(req.method === "POST" ? {id: JSON.parse(body).name, state: "OFFLINE"} : {items: []}));
  });
  const baseUrl = await listen(backend);
  const middleware = createDeviceRegistrationMiddleware({managementUrl: baseUrl});
  const preview = createServer((req, res) => middleware(req, res, () => {res.statusCode = 404;res.end();}));
  const url = await listen(preview);
  try {await action(url, calls, baseUrl);} finally {await Promise.all([new Promise(resolve => preview.close(resolve)), new Promise(resolve => backend.close(resolve))]);}
}

test("native sensor registration preserves the management API session and CSRF contract", async () => {
  await withServers(async (url, calls, baseUrl) => {
    const csrf = await fetch(`${url}/api/control-plane/csrf`);
    assert.equal(csrf.status, 200);
    assert.match(csrf.headers.get("set-cookie"), /EDGEAI_SESSION=fixture-session/);
    const token = (await csrf.json()).token;
    const body = {name: "sensor-temperature", protocol: "mqtt", properties: [{name: "temperature", dataType: "float32", topic: "sensor/input"}]};
    const response = await fetch(`${url}/api/control-plane/managed-devices`, {method: "POST", headers: {
      Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": token,
      Cookie: "kestraBasicAuthenticated=true; EDGEAI_SESSION=fixture-session; other=discard",
    }, body: JSON.stringify(body)});
    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), {id: "sensor-temperature", state: "OFFLINE"});
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(calls[1].headers.cookie, "EDGEAI_SESSION=fixture-session");
    assert.equal(calls[1].headers.origin, baseUrl);
    assert.deepEqual(JSON.parse(calls[1].body), body);
    assert.equal((await fetch(`${url}/api/control-plane/managed-devices`)).status, 200);
    assert.equal((await fetch(`${url}/api/control-plane/device-profiles`)).status, 200);
  });
});

test("editing a measurement uses the current profile and changes only that property", async () => {
  await withServers(async (url, calls) => {
    const detail = `${url}/api/control-plane/managed-devices/sensor-a`;
    const original = await fetch(detail);
    assert.equal(original.status, 200);
    const before = await original.json();
    const updated = {name: "temperature", dataType: "float32", sensor: "temperature", jsonPath: "raw", scale: 0.1, offset: 2, unit: "C"};
    const saved = await fetch(`${detail}/properties/temperature`, {method: "PUT", headers: {Origin: url, "Content-Type": "application/json", "X-CSRF-TOKEN": "fixture-token", Cookie: "EDGEAI_SESSION=fixture-session"}, body: JSON.stringify(updated)});
    assert.equal(saved.status, 200);
    const after = await (await fetch(detail)).json();
    assert.deepEqual(after.profile.properties, [updated, before.profile.properties[1]]);
    assert.deepEqual(after.profile.protocolConfig, before.profile.protocolConfig);
    assert.equal(after.state, "OFFLINE");
    assert.equal(calls.find(call => call.method === "PUT").headers["x-csrf-token"], "fixture-token");
  });
});

test("property edit failures retain backend errors and cannot bypass same-origin or CSRF", async () => {
  await withServers(async (url, calls) => {
    const endpoint = `${url}/api/control-plane/managed-devices/sensor-a/properties/temperature`;
    const headers = {"Content-Type": "application/json"};
    assert.equal((await fetch(endpoint, {method: "PUT", headers: {...headers, Origin: "https://another.example"}, body: "{}"})).status, 403);
    assert.equal(calls.length, 0);
    const rejected = await fetch(endpoint, {method: "PUT", headers, body: "{}"});
    assert.equal(rejected.status, 403);
    assert.deepEqual(await rejected.json(), {message: "CSRF rejected"});
    const conflict = await fetch(endpoint, {method: "PUT", headers: {...headers, "X-CSRF-TOKEN": "fixture-token"}, body: '{"name":"conflict"}'});
    assert.equal(conflict.status, 409);
    assert.deepEqual(await conflict.json(), {message: "같은 이름의 장치가 등록되어 있습니다."});
  });
});

test("single property addition and deletion forward only the exact managed route, session and CSRF", async () => {
  await withServers(async (url, calls) => {
    const headers = {Origin: url, Cookie: "EDGEAI_SESSION=fixture-session; ignored=secret", "X-CSRF-TOKEN": "fixture-token"};
    const collection = `${url}/api/control-plane/managed-devices/sensor-a/properties`;
    const property = {name: "humidity", dataType: "int32", sensor: "humidity", jsonPath: "raw", scale: 1, offset: 0, unit: "raw"};
    assert.equal((await fetch(collection, {method: "POST", headers: {...headers, "Content-Type": "application/json"}, body: JSON.stringify(property)})).status, 201);
    assert.equal((await fetch(`${collection}/temperature`, {method: "DELETE", headers})).status, 200);
    assert.deepEqual(calls.map(call => [call.method, call.path, call.headers.cookie, call.headers["x-csrf-token"]]), [
      ["POST", "/api/devices/sensor-a/properties", "EDGEAI_SESSION=fixture-session", "fixture-token"],
      ["DELETE", "/api/devices/sensor-a/properties/temperature", "EDGEAI_SESSION=fixture-session", "fixture-token"],
    ]);
    assert.deepEqual(JSON.parse(calls[0].body), property);
    for (const [path, method, expected] of [["properties", "DELETE", 405], ["properties", "GET", 405], ["properties/temperature/extra", "DELETE", 404]])
      assert.equal((await fetch(`${url}/api/control-plane/managed-devices/sensor-a/${path}`, {method, headers})).status, expected, `${method} ${path}`);
    assert.equal((await fetch(`${collection}/temperature`, {method: "DELETE", headers: {...headers, Origin: "https://another.example"}})).status, 403);
    assert.equal(calls.length, 2);
  });
});

test("cross-origin registration and unrelated writes never reach the existing server", async () => {
  await withServers(async (url, calls) => {
    const headers = {Origin: "https://another.example", "Content-Type": "application/json"};
    assert.equal((await fetch(`${url}/api/control-plane/managed-devices`, {method: "POST", headers, body: "{}"})).status, 403);
    assert.equal((await fetch(`${url}/api/control-plane/csrf`, {headers: {"Sec-Fetch-Site": "cross-site"}})).status, 403);
    for (const [path, method, expected] of [["profiles/DEVICE", "POST", 404], ["managed-devices", "DELETE", 405], ["managed-devices/sensor-a", "PUT", 405], ["managed-devices/sensor-a/properties/temperature", "PATCH", 405], ["workflows/invalid", "POST", 404]]) {
      assert.equal((await fetch(`${url}/api/control-plane/${path}`, {method, headers: {"Content-Type": "application/json"}, body: "{}"})).status, expected);
    }
    assert.deepEqual(calls, []);
  });
});

test("backend CSRF rejection and duplicate sensor errors keep their status and message", async () => {
  await withServers(async url => {
    const headers = {"Content-Type": "application/json"};
    const rejected = await fetch(`${url}/api/control-plane/managed-devices`, {method: "POST", headers, body: '{"name":"sensor-a"}'});
    assert.equal(rejected.status, 403);
    assert.deepEqual(await rejected.json(), {message: "CSRF rejected"});
    const duplicate = await fetch(`${url}/api/control-plane/managed-devices`, {method: "POST", headers: {...headers, "X-CSRF-TOKEN": "fixture-token"}, body: '{"name":"conflict"}'});
    assert.equal(duplicate.status, 409);
    assert.deepEqual(await duplicate.json(), {message: "같은 이름의 장치가 등록되어 있습니다."});
  });
});

test("non-JSON, malformed and oversized registration inputs are rejected before forwarding", async () => {
  await withServers(async (url, calls) => {
    const endpoint = `${url}/api/control-plane/managed-devices`;
    assert.equal((await fetch(endpoint, {method: "POST", body: "plain"})).status, 415);
    assert.equal((await fetch(endpoint, {method: "POST", headers: {"Content-Type": "application/json"}, body: "{"})).status, 400);
    assert.equal((await fetch(endpoint, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({name: "x".repeat(66000)})})).status, 413);
    assert.deepEqual(calls, []);
  });
});

test("cancelling an unfinished upload leaves registration and reads available", async () => {
  await withServers(async (url, calls) => {
    await new Promise(resolve => {
      const upload = httpRequest(`${url}/api/control-plane/managed-devices`, {method: "POST", headers: {"Content-Type": "application/json", "Content-Length": "1000"}});
      upload.on("error", resolve);
      upload.write('{"name":"unfinished');
      setTimeout(() => upload.destroy(), 25);
    });
    assert.equal((await fetch(`${url}/api/control-plane/csrf`)).status, 200);
    assert.equal(calls.some(call => call.method === "POST"), false);
  });
});
