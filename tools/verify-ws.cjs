#!/usr/bin/env node
// Verify the bridge's WebSocket/Upgrade branch without needing a live WS route
// in DSH: point the bridge at a local echo server and talk through it.
// Covers both the 101 upgrade path and the gate on that path.
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

let WebSocket, WebSocketServer;
for (const base of [
  path.join(process.env.USERPROFILE ?? "", ".dsh", "profiles", "desktop", "node_modules", "ws"),
  path.join(process.env.USERPROFILE ?? "", ".dsh", "profiles", "web", "node_modules", "ws"),
]) {
  if (fs.existsSync(base)) {
    ({ WebSocket, WebSocketServer } = require(base));
    break;
  }
}
if (!WebSocketServer) throw new Error("ws not found in DSH profiles");

const ECHO_PORT = 7399;
const BRIDGE_PORT = 3081;
const KEY = "selftest-key";

const echoHttp = http.createServer((_q, s) => s.end("not a websocket"));
const wss = new WebSocketServer({ server: echoHttp });
wss.on("connection", (ws) => ws.on("message", (m) => ws.send("echo:" + m)));

const echoReady = new Promise((r) => echoHttp.listen(ECHO_PORT, "127.0.0.1", r));

echoReady.then(async () => {
  const child = spawn(
    process.execPath,
    [
      path.join(__dirname, "dsh-lan-bridge.cjs"),
      "--port",
      String(BRIDGE_PORT),
      "--upstream",
      `127.0.0.1:${ECHO_PORT}`,
      "--key",
      KEY,
    ],
    { stdio: "ignore" },
  );
  const stop = () => {
    child.kill();
    echoHttp.close();
    wss.close();
    process.exit(0);
  };
  await new Promise((r) => setTimeout(r, 1200));

  const attempt = (label, cookie) =>
    new Promise((resolve) => {
      const ws = new WebSocket(`ws://127.0.0.1:${BRIDGE_PORT}/anything`, {
        headers: { cookie: cookie ? `dsh-bridge=${KEY}` : "" },
      });
      let settled = false;
      const done = (msg) => {
        if (settled) return;
        settled = true;
        console.log(`  ${label}: ${msg}`);
        try {
          ws.terminate();
        } catch {}
        resolve();
      };
      ws.on("open", () => ws.send("ping"));
      ws.on("message", (m) => done(`101 UPGRADED, roundtrip -> ${m}`));
      ws.on("unexpected-response", (_r, res) => done(`HTTP ${res.statusCode} (not upgraded)`));
      ws.on("error", (e) => done(`error ${e.message}`));
      setTimeout(() => done("timeout"), 5000);
    });

  console.log("WebSocket through the bridge:");
  await attempt("with gate cookie", true);
  await attempt("without gate cookie", false);
  stop();
});
