#!/usr/bin/env node
// tools/api-probe.cjs
// 证明「非浏览器客户端」（原生 App 内核）能经桥接驱动 DSH。
//
// 抓包确定的线上格式（tools\api-capture.cjs 从无头 Chrome 实测）：
//   1) 闸门：请求带 Cookie dsh-bridge=<key>；URL 里**不要**带 ?k=，
//      因为桥接对任何带 k 的请求都会先 303 去改 cookie（dsh-lan-bridge.cjs:230）。
//   2) 一元 RPC：POST /api/<namespace>/<method>      ← 端点是两级斜杠名，不是点分
//      Content-Type: application/json
//      {"type":"client-request","rpcId":"<uuid>","method":"<ns>/<method>","payload":{"args":{…}}}
//      → {"type":"server-response","rpcId":"…","result":{"ok":true,"value":…}}
//   3) 多路复用流：WS /api/remote.mux
//      {"type":"open","streamId":"<uuid>","endpoint":"$events","payload":{"args":{}}}
//      ← {"type":"item","streamId":"…","value":{"type":"ready","clientId":"…","host":{"home":"…"}}}
//
// 用法：node tools\api-probe.cjs [host] [port] [watchSeconds]
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const crypto = require("crypto");

const KEY = fs.readFileSync(path.join(__dirname, ".bridge-key"), "utf8").trim();
const HOST = process.argv[2] || "192.168.31.216";
const PORT = Number(process.argv[3] || 3080);
const WATCH = Number(process.argv[4] || 6);
const WebSocket = require(path.join(os.homedir(), ".dsh", "profiles", "desktop", "node_modules", "ws"));

const started = Date.now();
const log = (...a) => console.log(`[${String(Date.now() - started).padStart(5)}ms]`, ...a);

function rpc(method, payload, cookie) {
  return new Promise((resolve, reject) => {
    const body = JSON.stringify({
      type: "client-request",
      rpcId: crypto.randomUUID(),
      method,
      payload: payload || { args: {} },
    });
    const headers = {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(body),
      Cookie: cookie || `dsh-bridge=${KEY}`,
    };
    const req = http.request({ host: HOST, port: PORT, path: `/api/${method}`, method: "POST", headers }, (res) => {
      let data = "";
      res.on("data", (c) => (data += c));
      res.on("end", () => {
        let parsed = null;
        try { parsed = JSON.parse(data); } catch { /* 非 JSON 就原样带出 */ }
        resolve({ status: res.statusCode, raw: data, parsed });
      });
    });
    req.on("error", reject);
    req.end(body);
  });
}

function summarize(value) {
  if (value == null) return "(empty)";
  if (Array.isArray(value)) return `Array(${value.length})`;
  if (typeof value === "object") {
    const keys = Object.keys(value);
    const inner = value.sessions || value.items || value.entries;
    if (Array.isArray(inner)) return `{${keys.join(",")}} -> ${inner.length} items`;
    return `{${keys.slice(0, 8).join(",")}${keys.length > 8 ? ",..." : ""}}`;
  }
  return String(value).slice(0, 80);
}

(async () => {
  log(`target http://${HOST}:${PORT}/  key ${KEY.slice(0, 4)}... (${KEY.length} chars)`);

  // --- 1. gate + unary RPC ------------------------------------------------
  const probe = await rpc("session/list", { args: { _request: {} } });
  log(`POST /api/session/list -> ${probe.status}`);
  if (probe.status !== 200) {
    log("body(400):", probe.raw.slice(0, 400));
    log("=> unary RPC failed");
    process.exit(1);
  }
  const env = probe.parsed;
  if (!env || env.type !== "server-response") {
    log("not a server-response envelope:", probe.raw.slice(0, 300));
    process.exit(1);
  }
  const result = env.result || {};
  log(`envelope ok: type=${env.type} ok=${result.ok}`);
  if (!result.ok) {
    log("rpc error:", JSON.stringify(result.error || {}).slice(0, 300));
    process.exit(1);
  }
  const sessions =
    (result.value && (result.value.sessions || result.value.items)) ||
    (Array.isArray(result.value) ? result.value : []);
  log(`session/list value: ${summarize(result.value)}`);
  const first = sessions[0];
  if (first) log(`first session: ${JSON.stringify(first).slice(0, 200)}`);

  // --- 2. multiplexed WebSocket ------------------------------------------
  const ws = new WebSocket(`ws://${HOST}:${PORT}/api/remote.mux`, { headers: { Cookie: `dsh-bridge=${KEY}` } });
  const counts = new Map();
  const samples = new Map();
  let ready = null;

  ws.on("open", () => {
    log("WS /api/remote.mux connected -> open $events + session/control");
    ws.send(JSON.stringify({ type: "open", streamId: "ev", endpoint: "$events", payload: { args: {} } }));
    ws.send(JSON.stringify({ type: "open", streamId: "s1", endpoint: "session/control", payload: { args: {} } }));
  });
  ws.on("message", (buf) => {
    let f;
    try { f = JSON.parse(buf.toString()); } catch { return; }
    if (f.type === "item") {
      counts.set(f.streamId, (counts.get(f.streamId) || 0) + 1);
      if (!samples.has(f.streamId)) {
        samples.set(f.streamId, JSON.stringify(f.value).slice(0, 240));
        log(`first item on ${f.streamId}: ${samples.get(f.streamId)}`);
      }
      if (f.streamId === "ev" && !ready && f.value && f.value.type === "ready") ready = f.value;
    } else if (f.type === "end") {
      log(`stream ${f.streamId} ended`);
    }
  });
  ws.on("error", (e) => log("WS error:", e.message));

  await new Promise((r) => setTimeout(r, WATCH * 1000));
  log(`--- ${WATCH}s summary ---`);
  log(`ready frame: ${ready ? JSON.stringify(ready).slice(0, 200) : "NONE"}`);
  log(`items per stream: ${JSON.stringify([...counts.entries()])}`);
  ws.close();
  const ok = Boolean(ready) && (counts.get("s1") || 0) > 0;
  log(ok ? "PASS: gate + unary RPC + multiplexed stream all work through the bridge" : "FAIL: some hop did not work");
  process.exit(ok ? 0 : 1);
})().catch((e) => {
  console.error("probe failed:", e && e.message);
  process.exit(1);
});