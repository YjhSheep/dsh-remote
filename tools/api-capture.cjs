#!/usr/bin/env node
// tools/api-capture.cjs
// 用无头 Chrome 打开桥接地址，抓 DSH Web UI **真实**发出的 HTTP API 调用与 WebSocket 帧。
// 目的：让原生客户端照着真客户端的 URL / 方法 / 载荷 / 帧格式实现，而不是猜。
// 用法：node tools\api-capture.cjs [host] [port] [seconds]
"use strict";

const { spawn, execFileSync } = require("child_process");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");

const WebSocket = require(
  path.join(os.homedir(), ".dsh", "profiles", "desktop", "node_modules", "ws"),
);
const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const KEY = fs.readFileSync(path.join(__dirname, ".bridge-key"), "utf8").trim();
const HOST = process.argv[2] || "192.168.31.216";
const PORT = Number(process.argv[3] || 3080);
const SECS = Number(process.argv[4] || 10);
// --click-b64 <base64(UTF-8)>：点开第一条会话，观察 UI 如何拉取/订阅一段对话
const CLICK_B64 = (() => {
  const i = process.argv.indexOf("--click-b64");
  return i >= 0 && process.argv[i + 1] ? Buffer.from(process.argv[i + 1], "base64").toString("utf8") : null;
})();
// --click-file <path>：同上，但文本从 UTF-8 文件读（避免中文经控制台传参被 GBK 弄坏）
const CLICK_TEXT = CLICK_B64 || (() => {
  const i = process.argv.indexOf("--click-file");
  return i >= 0 && process.argv[i + 1] ? fs.readFileSync(process.argv[i + 1], "utf8").trim() : null;
})();
const PORT_CDP = 9223;
const BASE = `http://${HOST}:${PORT}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const getJson = (url) =>
  new Promise((resolve, reject) => {
    http
      .get(url, (res) => {
        let d = "";
        res.on("data", (c) => (d += c));
        res.on("end", () => {
          try { resolve(JSON.parse(d)); } catch (e) { reject(e); }
        });
      })
      .on("error", reject);
  });

const cut = (s, n) => (s === undefined ? undefined : String(s).replace(/\s+/g, " ").slice(0, n));

function connect(url) {
  const ws = new WebSocket(url);
  let id = 0;
  const pending = new Map();
  const urls = new Map();      // requestId -> url
  const methods = new Map();   // requestId -> method
  const calls = new Map();     // "METHOD /path" -> { n, status, sample, cookie }
  const wsUrls = new Map();    // requestId -> url
  const wsFrames = new Map();  // url -> lines
  const posts = new Map();     // requestId -> postData
  const hdrs = new Map();      // requestId -> headers

  ws.on("message", (raw) => {
    const msg = JSON.parse(raw);
    const p = msg.params || {};
    if (msg.method === "Network.requestWillBeSent") {
      urls.set(p.requestId, p.request.url);
      methods.set(p.requestId, p.request.method);
      if (p.request.postData !== undefined) posts.set(p.requestId, cut(p.request.postData, 400));
      hdrs.set(p.requestId, p.request.headers || {});
    } else if (msg.method === "Network.responseReceived") {
      const u = urls.get(p.requestId) || p.response.url;
      if (/\/api(\/|\?|$)/.test(u)) {
        const key = (methods.get(p.requestId) || "?") + " " + new URL(u).pathname;
        const rec = calls.get(key) || { n: 0, status: p.response.status, sample: undefined, headers: undefined };
        rec.n++;
        rec.status = p.response.status;
        if (rec.sample === undefined) {
          rec.sample = posts.get(p.requestId);
          rec.headers = hdrs.get(p.requestId);
        }
        calls.set(key, rec);
      }
    } else if (msg.method === "Network.webSocketCreated") {
      wsUrls.set(p.requestId, p.url);
      const k = new URL(p.url).pathname;
      if (!wsFrames.has(k)) wsFrames.set(k, []);
    } else if (msg.method === "Network.webSocketFrameSent" || msg.method === "Network.webSocketFrameReceived") {
      const k = new URL(wsUrls.get(p.requestId) || "http://x/").pathname;
      const arr = wsFrames.get(k) || wsFrames.set(k, []).get(k);
      if (arr.length < 40) arr.push((msg.method.endsWith("Sent") ? ">> " : "<< ") + cut(p.response?.payloadData, 300));
    } else if (msg.method === "Runtime.consoleAPICalled") {
      console.log("console: " + (p.args || []).map((a) => cut(a.value ?? a.description, 160)).join(" "));
    }
    const q = pending.get(msg.id);
    if (q) {
      pending.delete(msg.id);
      msg.error ? q.reject(new Error(JSON.stringify(msg.error))) : q.resolve(msg.result);
    }
  });
  const ready = new Promise((r) => ws.on("open", r));
  const send = (method, params) => {
    const n = ++id;
    ws.send(JSON.stringify({ id: n, method, params: params || {} }));
    return new Promise((resolve, reject) => pending.set(n, { resolve, reject }));
  };
  return { ready, send, calls, wsFrames, urls, close: () => ws.close() };
}

(async () => {
  const profile = path.join(os.tmpdir(), "dsh-api-capture");
  const chrome = spawn(
    CHROME,
    [
      "--headless=new",
      `--remote-debugging-port=${PORT_CDP}`,
      `--user-data-dir=${profile}`,
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-gpu",
      "about:blank",
    ],
    { stdio: "ignore" },
  );
  const kill = () => {
    try { execFileSync("taskkill", ["/PID", String(chrome.pid), "/T", "/F"], { stdio: "ignore" }); } catch {}
  };
  try {
    let target = null;
    for (let i = 0; i < 40 && !target; i++) {
      await sleep(250);
      try {
        const list = await getJson(`http://127.0.0.1:${PORT_CDP}/json/list`);
        target = list.find((t) => t.type === "page");
      } catch {}
    }
    if (!target) throw new Error("no CDP page target");

    const cdp = connect(target.webSocketDebuggerUrl);
    await cdp.ready;
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Network.enable");
    await cdp.send("Emulation.setDeviceMetricsOverride", { width: 400, height: 700, deviceScaleFactor: 3, mobile: true });
    console.log(`打开 ${BASE}/?k=<key>，抓 ${SECS}s …`);
    await cdp.send("Page.navigate", { url: `${BASE}/?k=${encodeURIComponent(KEY)}` });
    await sleep(Math.min(6000, SECS * 1000));

    if (CLICK_TEXT) {
      const js =
        "(function(){const t=" + JSON.stringify(CLICK_TEXT) +
        ";const els=[...document.querySelectorAll('body *')].filter(e=>e.textContent.trim()===t);" +
        "if(!els.length)return 'no element with that text ('+document.querySelectorAll('body *').length+' nodes)';" +
        "const el=els[els.length-1];el.click();return 'clicked '+el.tagName+'.'+String(el.className).slice(0,60);})()";
      const r = await cdp.send("Runtime.evaluate", { expression: js, returnByValue: true });
      console.log("click: " + JSON.stringify(r.result?.value));
    }

    await sleep(SECS * 1000);

    console.log("\n===== HTTP /api 调用 =====");
    if (cdp.calls.size === 0) console.log("（没有捕获到 /api 调用）");
    for (const [key, rec] of [...cdp.calls.entries()].sort()) {
      console.log(`${rec.status}  ${key}   ×${rec.n}`);
      if (rec.sample) console.log(`      body: ${rec.sample}`);
      if (rec.headers) {
        const h = rec.headers;
        console.log(`      ct=${h["Content-Type"] || h["content-type"]} origin=${h.Origin || h.origin || "-"} cookie=${(h.Cookie || h.cookie || "").slice(0, 40)} sfs=${h["Sec-Fetch-Site"] || "-"}`);
      }
    }

    console.log("\n===== WebSocket =====");
    if (cdp.wsFrames.size === 0) console.log("（没有捕获到 WS 连接）");
    for (const [k, arr] of cdp.wsFrames) {
      console.log(`--- ${k} ---`);
      for (const f of arr) console.log(f);
    }
    cdp.close();
  } finally {
    kill();
  }
})().catch((e) => {
  console.error("FAILED " + e.message);
  process.exit(1);
});