#!/usr/bin/env node
// Phase 0 LAN bridge for the running DSH desktop app.
//
// Why this exists: the desktop app binds 127.0.0.1 only, and the /api
// browser-trust fence (isTrustedApiRequest) refuses any request whose Host is
// not loopback or a configured trustedHosts entry. Host is a startup-immutable
// value, so without restarting DSH the only way a phone can reach /api is a
// local reverse proxy that rewrites Host/Origin back to the loopback authority
// and attaches a session cookie minted from the stored browser-session secret.
//
// No dependencies, nothing written into the DSH profile, DSH keeps running.
//
// It also carries the phone-side layer: tools/bridge/probe.js reports the real
// geometry of the device that opened the page (written to tools/layout.json),
// and tools/bridge/mobile.css — when present — is injected into the document and
// served live, so mobile changes show up on the phone after a refresh.
//
// Usage:  node tools/dsh-lan-bridge.cjs [--port 3080] [--upstream 127.0.0.1:19387]
//                                      [--key <token>] [--selftest]
"use strict";
const http = require("http");
const fs = require("fs");
const os = require("os");
const dgram = require("dgram");
const path = require("path");
const crypto = require("crypto");

const ROOT = path.join(__dirname, "..");
const KEY_FILE = path.join(__dirname, ".bridge-key");

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? fallback : process.argv[i + 1];
}
const PORT = Number(arg("port", 3080));
const UPSTREAM = arg("upstream", "127.0.0.1:19387");
const [UP_HOST, UP_PORT] = UPSTREAM.split(":");
const AUTHORITY = `${UP_HOST}:${UP_PORT}`;

function loadYaml(file) {
  const dirs = [
    process.env.DSH_YAML_PATH,
    path.join(os.homedir(), ".dsh", "profiles", "desktop", "node_modules", "js-yaml"),
    path.join(os.homedir(), ".dsh", "profiles", "web", "node_modules", "js-yaml"),
  ].filter(Boolean);
  const found = dirs.find((d) => fs.existsSync(path.join(d, "package.json")));
  if (!found) throw new Error("js-yaml not found; set DSH_YAML_PATH");
  return require(found).load(fs.readFileSync(file, "utf8"));
}

// --- DSH session cookie -----------------------------------------------------
// Mirrors @deepseek-ai/dsh-client-connection: name = dsh-auth-<b64url sha256(authority)>,
// value = v1.<b64url payload>.<b64url HMAC-SHA256(secret, payload)>. The signing
// secret is the stored browser-session grant, so no launch token is needed.
const DSH_HOME = process.env.DSH_HOME || path.join(os.homedir(), ".dsh");
const creds = loadYaml(path.join(DSH_HOME, ".credentials.yaml"));
const grant = creds.records["client-connection/browser-session"];
if (grant?.kind !== "grant" || grant.payload?.version !== 1)
  throw new Error("unexpected browser-session credential record");
const SECRET = Buffer.from(
  String(grant.payload.secret).replaceAll("-", "+").replaceAll("_", "/"),
  "base64",
);

const b64u = (v) =>
  Buffer.from(v).toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");

function sessionCookie(authority, maxAgeDays = 30) {
  const issuedAt = Date.now();
  const body = b64u(
    Buffer.from(
      JSON.stringify({
        version: 1,
        authority,
        issuedAt,
        expiresAt: issuedAt + maxAgeDays * 1440 * 60 * 1e3,
      }),
      "utf8",
    ),
  );
  const name = "dsh-auth-" + b64u(crypto.createHash("sha256").update(authority).digest());
  const sig = crypto.createHmac("sha256", SECRET).update(body).digest();
  return `${name}=v1.${body}.${b64u(sig)}`;
}
const UPSTREAM_COOKIE = sessionCookie(AUTHORITY);

// --- access gate ------------------------------------------------------------
// The proxied DSH session is full remote code execution on this PC, so the
// bridge authenticates the client itself. Random 128-bit token, in the URL once
// (which sets an HttpOnly cookie) or in a cookie already.
let KEY = arg("key", undefined);
if (!KEY) {
  if (fs.existsSync(KEY_FILE)) KEY = fs.readFileSync(KEY_FILE, "utf8").trim();
  else {
    KEY = crypto.randomBytes(16).toString("base64url");
    fs.writeFileSync(KEY_FILE, KEY + "\n", { mode: 0o600 });
  }
}
const GATE_NAME = "dsh-bridge";
const GATE_COOKIE = `${GATE_NAME}=${KEY}; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000`;

function parseCookies(header) {
  const out = {};
  for (const part of String(header ?? "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0) out[part.slice(0, i).trim()] = part.slice(i + 1).trim();
  }
  return out;
}

function gateOk(req) {
  const c = parseCookies(req.headers.cookie);
  if (c[GATE_NAME]) return c[GATE_NAME] === KEY;
  // also accept a bare ?k=<key> so a browser link works without the cookie
  const u = new URL(req.url, "http://x");
  const k = u.searchParams.get("k");
  if (k !== null) return k === KEY;
  return false;
}

/** Rewrite an incoming client request into one the loopback DSH will accept. */
function upstreamHeaders(req) {
  const headers = { ...req.headers };
  headers.host = AUTHORITY;
  headers.origin = `http://${AUTHORITY}`;
  delete headers["sec-fetch-site"];
  const cookies = parseCookies(req.headers.cookie);
  delete cookies[GATE_NAME];
  for (const n of Object.keys(cookies)) if (n.startsWith("dsh-auth-")) delete cookies[n];
  headers.cookie = [
    ...Object.entries(cookies).map(([k, v]) => `${k}=${v}`),
    UPSTREAM_COOKIE,
  ].join("; ");
  return headers;
}

const GATE_PAGE = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>DSH 桥接：需要访问密钥</title>
<style>body{font:16px/1.6 system-ui,sans-serif;margin:2rem;max-width:34rem}</style>
<h1>需要访问密钥</h1>
<p>这个地址是手机访问本机 DSH 的入口，必须带上访问密钥。</p>
<p>在电脑上双击 <code>tools\\bridge-on.cmd</code>（桥接平时会自动运行，这一步只是把它拉回来），
它会打印含密钥的完整网址；或把 <code>?k=密钥</code> 追加到当前地址后面。</p>`;

// --- phone-side layer -------------------------------------------------------
// Two things the raw GUI does not ship: a layout probe that reports whatever
// device actually opened the page (so mobile CSS is written against measured
// geometry, not guesses), and tools/bridge/mobile.css, served live so a change
// is visible on the phone after a refresh — no DSH restart, no profile edit.
const BRIDGE_DIR = path.join(__dirname, "bridge");
const NOTIFY_JS = path.join(BRIDGE_DIR, "notify.js");
const PROBE_JS = path.join(BRIDGE_DIR, "probe.js");
const MOBILE_CSS = path.join(BRIDGE_DIR, "mobile.css");
const HOST_TRUST_JS = path.join(BRIDGE_DIR, "owns-host.js");
// Marks html[data-dsh-kb] while the soft keyboard is up, so mobile.css can get the
// floating widget out of the composer's way without relying on :focus (Android
// keeps the field focused after the IME is dismissed, which hid it for good).
const KEYBOARD_JS = path.join(BRIDGE_DIR, "keyboard.js");
// Touch devices have no hover, so the widget's ☰ is on screen for good; this marks
// html[data-dsh-widget-idle] after a few seconds without a touch on the widget, which
// mobile.css fades the button out on.
const WIDGET_IDLE_JS = path.join(BRIDGE_DIR, "widget-idle.js");
// On a phone the sidebar is a floating panel over the conversation (mobile.css). Picking a
// session left it covering the chat until you tapped 收起侧边栏 again; this closes it.
const SIDEBAR_JS = path.join(BRIDGE_DIR, "sidebar.js");
const LAYOUT_JSON = path.join(__dirname, "layout.json");
const APP_APK = path.join(__dirname, "..", "android", "dist", "dsh-remote.apk");

function serveLocal(res, file, type) {
  if (!fs.existsSync(file)) {
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("not found\n");
    return;
  }
  res.writeHead(200, { "content-type": type, "cache-control": "no-store" });
  fs.createReadStream(file).pipe(res);
}

function handleProbe(req, res) {
  let body = "";
  req.setEncoding("utf8");
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    try {
      const data = JSON.parse(body);
      fs.writeFileSync(LAYOUT_JSON, JSON.stringify(data, null, 2));
      const v = data.viewport ?? {};
      console.log(
        `probe[${data.tag}] ${v.innerWidth}x${v.innerHeight} dpr=${v.dpr} ` +
          `safe=${JSON.stringify(data.safeArea)} -> tools/layout.json`,
      );
    } catch (e) {
      console.log(`probe: bad payload ${e.message}`);
    }
    res.writeHead(204);
    res.end();
  });
}

/** Add the phone-side layer to a top-level document on its way to the device. */
function injectHtml(html) {
  // No viewport-fit=cover: measured env(safe-area-inset-*) is 0 all round and the
  // browser already keeps its chrome clear, so opting in would only shove content
  // under the status/gesture bars.
  const tags =
    (fs.existsSync(MOBILE_CSS) ? '<link rel="stylesheet" href="/__bridge/mobile.css">' : "") +
    // Owns-host is NOT deferred: it has to be installed before any module script runs.
    (fs.existsSync(HOST_TRUST_JS) ? '<script src="/__bridge/owns-host.js"></script>' : "") +
    (fs.existsSync(KEYBOARD_JS) ? '<script src="/__bridge/keyboard.js"></script>' : "") +
    (fs.existsSync(NOTIFY_JS) ? '<script src="/__bridge/notify.js"></script>' : "") +
    (fs.existsSync(WIDGET_IDLE_JS) ? '<script src="/__bridge/widget-idle.js" defer></script>' : "") +
    (fs.existsSync(SIDEBAR_JS) ? '<script src="/__bridge/sidebar.js" defer></script>' : "") +
    '<script src="/__bridge/probe.js" defer></script>';
  return /<\/body>/i.test(html) ? html.replace(/<\/body>/i, tags + "</body>") : html + tags;
}

// One line per request. A phone that shows an error page cannot say whether its request ever
// reached us: tools/bridge.log only records startup and probe events, and /api traffic leaves no
// trace at all. The byte count we actually wrote back is what separates "never arrived" from
// "answered here and lost on the way back" — which is exactly the App-over-the-tunnel question.
const REQLOG = path.join(__dirname, "bridge.req.log");
function logReq(text) {
  try {
    fs.appendFileSync(REQLOG, `${new Date().toISOString()} ${text}\n`);
  } catch (e) {
    // a log must never break the proxy
  }
}

const server = http.createServer((req, res) => {
  let out = 0;
  const write = res.write.bind(res);
  const end = res.end.bind(res);
  res.write = (chunk, ...rest) => {
    if (chunk) out += Buffer.byteLength(chunk);
    return write(chunk, ...rest);
  };
  res.end = (chunk, ...rest) => {
    if (chunk) out += Buffer.byteLength(chunk);
    return end(chunk, ...rest);
  };
  const started = Date.now();
  res.on("finish", () =>
    logReq(
      `${req.method} ${req.headers.host || "-"} ${req.url} from ${req.socket.remoteAddress} -> ` +
        `${res.statusCode} ${out}B ${Date.now() - started}ms`,
    ),
  );
  if (!gateOk(req)) {
    res.writeHead(401, { "content-type": "text/html; charset=utf-8" });
    res.end(GATE_PAGE);
    return;
  }
  const u = new URL(req.url, "http://x");
  if (u.pathname.startsWith("/__bridge/")) {
    if (u.pathname === "/__bridge/probe.js") return serveLocal(res, PROBE_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/mobile.css") return serveLocal(res, MOBILE_CSS, "text/css; charset=utf-8");
    if (u.pathname === "/__bridge/owns-host.js")
      return serveLocal(res, HOST_TRUST_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/keyboard.js")
      return serveLocal(res, KEYBOARD_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/notify.js") return serveLocal(res, NOTIFY_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/widget-idle.js")
      return serveLocal(res, WIDGET_IDLE_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/sidebar.js") return serveLocal(res, SIDEBAR_JS, "text/javascript; charset=utf-8");
    if (u.pathname === "/__bridge/probe" && req.method === "POST") return handleProbe(req, res);
    if (u.pathname === "/__bridge/state")
      return serveLocal(res, LAYOUT_JSON, "application/json; charset=utf-8");
    // Live LAN address list for the App: it asks through the tunnel (a fixed address that works
    // from any network), then tries each candidate directly and keeps the first that answers.
    if (u.pathname === "/__bridge/lan.json") return serveLanJson(res);
    // Delivery path for the phone app: open this on the phone and Android installs it
    // straight from the PC. 404s until android/gradle-build.ps1 has produced the APK.
    if (u.pathname === "/__bridge/app.apk")
      return serveLocal(res, APP_APK, "application/vnd.android.package-archive");
    res.writeHead(404, { "content-type": "text/plain; charset=utf-8" });
    res.end("not found\n");
    return;
  }
  if (u.searchParams.get("k") === KEY) {
    u.searchParams.delete("k");
    res.writeHead(303, {
      location: u.pathname + (u.search || ""),
      "set-cookie": GATE_COOKIE,
      "content-type": "text/plain; charset=utf-8",
    });
    res.end("redirecting");
    return;
  }
  const upHeaders = upstreamHeaders(req);
  // The document is the only response that ever needs rewriting, so ask for it
  // uncompressed and stream everything else (SSE especially) straight through.
  const isDocument =
    (req.headers["sec-fetch-dest"] === undefined ||
      req.headers["sec-fetch-dest"] === "document") &&
    /text\/html/i.test(String(req.headers.accept ?? ""));
  if (isDocument) delete upHeaders["accept-encoding"];
  const up = http.request(
    { host: UP_HOST, port: Number(UP_PORT), path: req.url, method: req.method, headers: upHeaders },
    (upRes) => {
      const headers = { ...upRes.headers };
      // host-only cookies: the phone must store them for the LAN host, not loopback
      if (headers["set-cookie"])
        headers["set-cookie"] = [].concat(headers["set-cookie"]).map((c) =>
          c.replace(/;\s*domain=[^;]*/i, ""),
        );
      if (!isDocument || !/text\/html/i.test(String(headers["content-type"] ?? ""))) {
        res.writeHead(upRes.statusCode, headers);
        res.flushHeaders?.();
        upRes.pipe(res);
        return;
      }
      const chunks = [];
      upRes.on("data", (c) => chunks.push(c));
      upRes.on("end", () => {
        const injected = Buffer.from(
          injectHtml(Buffer.concat(chunks).toString("utf8")),
          "utf8",
        );
        delete headers["content-encoding"];
        delete headers["transfer-encoding"];
        headers["content-length"] = String(injected.length);
        headers["cache-control"] = "no-store";
        res.writeHead(upRes.statusCode, headers);
        res.end(injected);
      });
    },
  );
  up.on("error", (e) => {
    res.writeHead(502, { "content-type": "text/plain; charset=utf-8" });
    res.end(`bridge: upstream ${AUTHORITY} ${e.message}\n`);
  });
  req.pipe(up);
});

// WebSocket / any Upgrade: the client stream lives here, so this must work.
server.on("upgrade", (req, socket, head) => {
  logReq(
    `UPGRADE ${req.headers.host || "-"} ${req.url} from ${req.socket.remoteAddress} ` +
      `-> ${gateOk(req) ? "proxied" : "401"}`,
  );
  if (!gateOk(req)) {
    socket.end("HTTP/1.1 401 Unauthorized\r\n\r\n");
    return;
  }
  const up = http.request({
    host: UP_HOST,
    port: Number(UP_PORT),
    path: req.url,
    method: req.method,
    headers: { ...upstreamHeaders(req), connection: "Upgrade", upgrade: req.headers.upgrade },
  });
  up.on("upgrade", (upRes, upSocket, upHead) => {
    socket.write(
      `HTTP/1.1 101 Switching Protocols\r\n${Object.entries(upRes.headers)
        .map(([k, v]) => `${k}: ${v}`)
        .join("\r\n")}\r\n\r\n`,
    );
    if (upHead?.length) socket.write(upHead);
    upSocket.pipe(socket);
    socket.pipe(upSocket);
    const bye = () => {
      upSocket.destroy();
      socket.destroy();
    };
    upSocket.on("error", bye);
    socket.on("error", bye);
  });
  up.on("error", () => socket.destroy());
  up.end();
});

function lanAddresses() {
  return Object.values(os.networkInterfaces())
    .flat()
    // skip link-local (169.254.x.x): never reachable from the phone, only noise
    .filter((i) => i && i.family === "IPv4" && !i.internal && !i.address.startsWith("169.254."))
    .map((i) => i.address);
}

// The address the OS itself would leave by: the one a phone on the same network can dial.
// udp4 connect() picks the route without sending a packet, so it works with no internet.
function primaryLanAddress(callback) {
  const socket = dgram.createSocket("udp4");
  let done = false;
  const finish = (value) => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    try {
      socket.close();
    } catch (e) {
      // already closed
    }
    callback(value);
  };
  const timer = setTimeout(() => finish(null), 500);
  socket.once("error", () => finish(null));
  socket.connect(53, "1.1.1.1", () => finish(socket.address().address));
}

// The start-up print is a snapshot, and the scheduled task starts this process before the
// adapters are up — so "(no non-internal IPv4 found)" can sit in the log while the machine is
// perfectly reachable. Re-print whenever the set actually changes; the route below calls this
// too, so a phone asking for the live list also refreshes the human-readable log.
let loggedLan = null;
function logLanUrls() {
  const lan = lanAddresses();
  const stamp = lan.join(",");
  if (stamp === loggedLan) return;
  loggedLan = stamp;
  for (const a of lan) console.log(`  phone URL: http://${a}:${PORT}/?k=${KEY}`);
  if (!lan.length) console.log("  (no non-internal IPv4 found)");
}

/** Live candidate list for the App: which addresses it should try to reach directly. */
function serveLanJson(res) {
  primaryLanAddress((primary) => {
    const all = lanAddresses();
    const addresses =
      primary && all.includes(primary) ? [primary, ...all.filter((a) => a !== primary)] : all;
    logLanUrls();
    res.writeHead(200, {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    });
    res.end(`${JSON.stringify({ port: PORT, primary: primary ?? null, addresses }, null, 2)}\n`);
  });
}

// The scheduled task re-runs this every few minutes to keep the bridge up, so "port
// already taken" is the normal, healthy case rather than a failure: report it only
// when a human is watching a console, and exit 0 both ways.
server.on("error", (e) => {
  if (e.code === "EADDRINUSE") {
    if (process.stdout.isTTY) console.log(`dsh-lan-bridge: port ${PORT} already served — nothing to do`);
    process.exit(0);
  }
  console.error(`dsh-lan-bridge: ${e.stack ?? e.message}`);
  process.exit(1);
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`${new Date().toISOString()} dsh-lan-bridge: listening on 0.0.0.0:${PORT} -> http://${AUTHORITY}`);
  logLanUrls();
  if (process.argv.includes("--selftest")) runSelfTest(lanAddresses()[0] ?? "127.0.0.1");
});

function request(host, path, headers = {}) {
  return new Promise((resolve) => {
    const req = http.request(
      { host, port: PORT, path, method: "GET", headers: { host: `${host}:${PORT}`, ...headers } },
      (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (c) => (body += c));
        res.on("end", () => resolve({ status: res.statusCode, headers: res.headers, body }));
      },
    );
    req.on("error", (e) => resolve({ status: "ERR", error: e.message }));
    req.end();
  });
}

async function runSelfTest(lanHost) {
  const checks = [
    ["no gate, no key", async () => (await request(lanHost, "/")).status],
    ["bad key", async () => (await request(lanHost, "/?k=wrong")).status],
    ["good key -> cookie", async () => (await request(lanHost, `/?k=${KEY}`)).status],
    ["good key in cookie, /", async () => (await request(lanHost, "/", { cookie: GATE_COOKIE })).status],
    ["good key in cookie, /api", async () => (await request(lanHost, "/api", { cookie: GATE_COOKIE })).status],
    [
      "document injection",
      async () => {
        const r = await request(lanHost, "/", { cookie: GATE_COOKIE, accept: "text/html" });
        return `${r.status} probe-script=${/__bridge\/probe\.js/.test(r.body ?? "")} kb-script=${/__bridge\/keyboard\.js/.test(
          r.body ?? "",
        )}`;
      },
    ],
    [
      "probe.js served",
      async () => (await request(lanHost, "/__bridge/probe.js", { cookie: GATE_COOKIE })).status,
    ],
    [
      "keyboard.js served",
      async () => (await request(lanHost, "/__bridge/keyboard.js", { cookie: GATE_COOKIE })).status,
    ],
  ];
  for (const [label, fn] of checks) console.log(`  selftest ${label}: ${await fn()}`);
  console.log(
    "  expect 401, 401, 303, 200, 404(=fence+auth passed, no such route), 200 probe-script=true kb-script=true, 200, 200",
  );
}
