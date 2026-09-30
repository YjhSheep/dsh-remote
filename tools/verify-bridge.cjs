#!/usr/bin/env node
// End-to-end checks of tools/dsh-lan-bridge.cjs against the live DSH app:
//   1. the page served over the LAN host is byte-identical to the authenticated
//      loopback page (proves Host rewriting + cookie injection)
//   2. the GUI's real live transport, SSE on /plugins/events, streams through
// Usage: node tools/verify-bridge.cjs [lanHost]
"use strict";
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");

const ROOT = path.join(__dirname, "..");
const KEY = fs.readFileSync(path.join(__dirname, ".bridge-key"), "utf8").trim();
const LAN = process.argv[2] ?? "192.168.31.216";
const PORT = 3080;
const UPSTREAM_PORT = 19387;

// mirror the bridge's cookie minting so the "direct" call is authenticated too
const YAML_DIRS = [
  path.join(os.homedir(), ".dsh", "profiles", "desktop", "node_modules", "js-yaml"),
  path.join(os.homedir(), ".dsh", "profiles", "web", "node_modules", "js-yaml"),
];
const yaml = require(YAML_DIRS.find((d) => fs.existsSync(d)));
const DSH_HOME = process.env.DSH_HOME || path.join(os.homedir(), ".dsh");
const grant = yaml.load(fs.readFileSync(path.join(DSH_HOME, ".credentials.yaml"), "utf8"))
  .records["client-connection/browser-session"];
const SECRET = Buffer.from(
  String(grant.payload.secret).replaceAll("-", "+").replaceAll("_", "/"),
  "base64",
);
const b64u = (v) =>
  Buffer.from(v).toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
const AUTHORITY = `127.0.0.1:${UPSTREAM_PORT}`;
const issuedAt = Date.now();
const body = b64u(
  Buffer.from(JSON.stringify({ version: 1, authority: AUTHORITY, issuedAt, expiresAt: issuedAt + 2592e6 }), "utf8"),
);
const DIRECT_COOKIE = `dsh-auth-${b64u(crypto.createHash("sha256").update(AUTHORITY).digest())}=v1.${body}.${b64u(
  crypto.createHmac("sha256", SECRET).update(body).digest(),
)}`;

function get(host, port, p, headers = {}) {
  return new Promise((resolve) => {
    const req = http.request(
      { host, port, path: p, headers: { host: `${host}:${port}`, ...headers } },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () =>
          resolve({ status: res.statusCode, body: Buffer.concat(chunks), headers: res.headers }),
        );
      },
    );
    req.on("error", (e) => resolve({ status: "ERR", error: e.message }));
    req.end();
  });
}

/** Read an SSE response for a few seconds and report what arrived. */
function readStream(host, port, p, headers, ms = 4000) {
  return new Promise((resolve) => {
    const req = http.request(
      { host, port, path: p, headers: { host: `${host}:${port}`, ...headers } },
      (res) => {
        let data = "";
        let events = 0;
        res.setEncoding("utf8");
        res.on("data", (c) => {
          data += c;
          events += (c.match(/\n\n/g) ?? []).length;
        });
        const finish = () => {
          req.destroy();
          resolve({ status: res.statusCode, headers: res.headers, bytes: data.length, events, head: data.slice(0, 300) });
        };
        setTimeout(finish, ms);
        res.on("end", finish);
      },
    );
    req.on("error", (e) => resolve({ status: "ERR", error: e.message }));
    req.end();
  });
}
const sha = (b) => crypto.createHash("sha256").update(b).digest("hex").slice(0, 16);

(async () => {
  // The bridge adds the phone-side layer to documents, so compare the page with
  // that layer removed: anything else differing means the proxy broke the page.
  const strip = (b) =>
    b
      ? Buffer.from(
          String(b)
            .replace(/<link rel="stylesheet" href="\/__bridge\/mobile\.css">/u, "")
            .replace(/<script src="\/__bridge\/owns-host\.js"><\/script>/u, "")
            .replace(/<script src="\/__bridge\/keyboard\.js"><\/script>/u, "")
            .replace(/<script src="\/__bridge\/probe\.js" defer><\/script>/u, "")
            .replace(/, viewport-fit=cover/u, ""),
          "utf8",
        )
      : b;
  const viaBridge = await get(LAN, PORT, "/", {
    cookie: `dsh-bridge=${KEY}`,
    accept: "text/html,application/xhtml+xml",
    "sec-fetch-dest": "document",
  });
  const direct = await get("127.0.0.1", UPSTREAM_PORT, "/", { cookie: DIRECT_COOKIE });
  const injected = /__bridge\/probe\.js/u.test(String(viaBridge.body));
  console.log(`[1] LAN  ${LAN}:${PORT}     status=${viaBridge.status} len=${viaBridge.body?.length} sha=${viaBridge.body ? sha(viaBridge.body) : "-"} injected=${injected}`);
  console.log(`    direct 127.0.0.1:${UPSTREAM_PORT} status=${direct.status} len=${direct.body?.length} sha=${direct.body ? sha(direct.body) : "-"}`);
  const a = strip(viaBridge.body);
  const b = strip(direct.body);
  console.log(`    => ${a && b && a.equals(b) ? "IDENTICAL once the injected layer is removed" : "DIFFERENT (page was changed by the proxy!)"}`);
  if (viaBridge.body) fs.writeFileSync(path.join(ROOT, "tmp", "bridge-index.html"), viaBridge.body);

  console.log("[2] SSE /plugins/events through the bridge");
  const sse = await readStream(LAN, PORT, "/plugins/events", { cookie: `dsh-bridge=${KEY}` });
  console.log(
    `    status=${sse.status} content-type=${sse.headers?.["content-type"]} bytes=${sse.bytes} events=${sse.events}`,
  );
  console.log(`    head: ${JSON.stringify(String(sse.head ?? sse.error).slice(0, 200))}`);
  const sseDirect = await readStream("127.0.0.1", UPSTREAM_PORT, "/plugins/events", { cookie: DIRECT_COOKIE });
  console.log(
    `    direct loopback: status=${sseDirect.status} bytes=${sseDirect.bytes} events=${sseDirect.events}`,
  );

  console.log("[3] phone-side layer");
  const cssPath = path.join(__dirname, "bridge", "mobile.css");
  const css = await get(LAN, PORT, "/__bridge/mobile.css", { cookie: `dsh-bridge=${KEY}` });
  console.log(
    `    mobile.css on disk=${fs.existsSync(cssPath)} served=${css.status} ${css.headers?.["content-type"]} bytes=${css.body?.length}`,
  );
  console.log(
    `    linked in document=${/href="\/__bridge\/mobile\.css"/u.test(String(viaBridge.body))} probe in document=${injected}`,
  );
  const trust = await get(LAN, PORT, "/__bridge/owns-host.js", { cookie: `dsh-bridge=${KEY}` });
  console.log(
    `    owns-host.js served=${trust.status} bytes=${trust.body?.length} in document=${/src="\/__bridge\/owns-host\.js"/u.test(String(viaBridge.body))} (claims ownsHost so a LAN page gets the host settings surfaces)`,
  );
  const kb = await get(LAN, PORT, "/__bridge/keyboard.js", { cookie: `dsh-bridge=${KEY}` });
  console.log(
    `    keyboard.js served=${kb.status} bytes=${kb.body?.length} in document=${/src="\/__bridge\/keyboard\.js"/u.test(String(viaBridge.body))} (marks html[data-dsh-kb] while the soft keyboard is up)`,
  );
})();
