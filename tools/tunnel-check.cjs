#!/usr/bin/env node
// tools/tunnel-check.cjs
// 从「外面」的地址测一遍：能不能连上、密钥对不对、DSH 认不认这个客户端。
// 内网地址、花生壳/natapp 给的公网域名、Tailscale 的 100.x 地址都可以当参数传。
//
// 用法: node tools\tunnel-check.cjs [host] [port] [scheme]
//   node tools\tunnel-check.cjs                          # 默认走内网 192.168.31.216:3080
//   node tools\tunnel-check.cjs xxx.vicp.net 80          # 花生壳的地址
//   node tools\tunnel-check.cjs my-host.ts.net 3080 https # Tailscale
//
// 只读：只调 session/list，不会动任何会话。
"use strict";

const fs = require("fs");
const path = require("path");
const http = require("http");
const https = require("https");
const net = require("net");
const crypto = require("crypto");

const HOST = process.argv[2] || "192.168.31.216";
const PORT = Number(process.argv[3] || 3080);
const SCHEME = (process.argv[4] || "http").toLowerCase();
const KEY = fs.readFileSync(path.join(__dirname, ".bridge-key"), "utf8").trim();
const started = Date.now();

const log = (...a) => console.log(`[${String(Date.now() - started).padStart(5)}ms]`, ...a);
let failures = 0;
const pass = (m) => log("PASS", m);
const fail = (m) => {
  failures++;
  log("FAIL", m);
};

// --- 1. TCP 能连上吗（域名解析 + 端口通不通）-------------------------------
function tcp() {
  return new Promise((resolve) => {
    const s = new net.Socket();
    const done = (ok, why) => {
      s.destroy();
      resolve({ ok, why });
    };
    s.setTimeout(6000, () => done(false, "timeout after 6s"));
    s.once("error", (e) => done(false, e.code || e.message));
    s.connect(PORT, HOST, () => done(true, ""));
  });
}

// --- 2. 闸门 + 一元 RPC -----------------------------------------------------
function rpc(method, payload) {
  const agent = SCHEME === "https" ? https : http;
  const body = JSON.stringify({
    type: "client-request",
    rpcId: crypto.randomUUID(),
    method,
    payload: payload || { args: {} },
  });
  return new Promise((resolve, reject) => {
    const req = agent.request(
      {
        host: HOST,
        port: PORT,
        path: `/api/${method}`,
        method: "POST",
        // URL 里绝不能带 ?k=：桥接会先 303 去把它换成 cookie，原生客户端就会拿到重定向。
        headers: {
          "Content-Type": "application/json",
          "Content-Length": Buffer.byteLength(body),
          Cookie: `dsh-bridge=${KEY}`,
        },
      },
      (res) => {
        let data = "";
        res.on("data", (c) => (data += c));
        res.on("end", () => {
          let parsed = null;
          try {
            parsed = JSON.parse(data);
          } catch {
            /* 非 JSON 就原样报出来 */
          }
          resolve({ status: res.statusCode, raw: data, parsed });
        });
      }
    );
    req.on("error", reject);
    req.end(body);
  });
}

(async () => {
  log(`target ${SCHEME}://${HOST}:${PORT}  key ${KEY.slice(0, 4)}... (${KEY.length} chars)`);

  const t = await tcp();
  if (t.ok) pass("tcp connect");
  else fail(`tcp connect: ${t.why}`);
  if (!t.ok) {
    log("");
    log("先把隧道/桥接跑起来，再重跑一次。桥接没起来时，先运行 tools\\bridge-on.cmd（或让开机自启的服务拉起它）。");
    process.exit(1);
  }

  let res;
  try {
    res = await rpc("session/list", { args: { _request: {} } });
  } catch (e) {
    fail(`request error: ${e.message}`);
    process.exit(1);
  }

  if (res.status === 200) pass("gate accepted, session/list -> 200");
  else if (res.status === 303) fail("gate 303 (redirect) - the URL carried ?k=; use the cookie only");
  else if (res.status === 401) fail("gate 401 - no/invalid dsh-bridge cookie (key mismatch?)");
  else if (res.status === 403) fail("gate 403 - bridge refused the host header (tunnel changed Host?)");
  else fail(`session/list -> HTTP ${res.status}: ${res.raw.slice(0, 200)}`);

  if (res.parsed && res.parsed.result) {
    if (res.parsed.result.ok) {
      const value = res.parsed.result.value || {};
      const items = value.items || value.sessions || [];
      pass(`rpc ok, ${items.length} session(s) visible`);
    } else {
      fail(`rpc error: ${JSON.stringify(res.parsed.result.error)}`);
    }
  } else if (res.status === 200) {
    fail(`not a dsh rpc response: ${res.raw.slice(0, 200)}`);
  }

  // --- 3. 顺带看一眼网页版是否也通（隧道转发静态资源的能力）-----------------
  try {
    const page = await rpc("$events", { args: {} });
    if (page.status === 404) pass("$events is a stream endpoint (404 on POST is expected)");
  } catch {
    /* 忽略 */
  }

  log("");
  if (failures === 0) {
    log("RESULT: OK - this address works from wherever you just ran it.");
    log(`App 里填：${HOST}:${PORT}`);
    log(`密钥：${KEY}`);
    if (SCHEME === "https") log(`（HTTPS 地址请把整条 URL 贴进 App：https://${HOST}:${PORT}/）`);
  } else {
    log(`RESULT: ${failures} check(s) failed - see FAIL lines above.`);
  }
  process.exit(failures === 0 ? 0 : 1);
})();