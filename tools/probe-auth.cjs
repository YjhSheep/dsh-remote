#!/usr/bin/env node
// Prove the /api browser-trust fence + cookie authentication model without
// printing the signing secret or the cookie it mints.
//
// Usage: node tools/probe-auth.cjs <hostHeader> <path> [cookie 0|1] [port]
"use strict";
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");

const YAML_PATHS = [
  process.env.DSH_YAML_PATH,
  path.join(os.homedir(), ".dsh", "profiles", "desktop", "node_modules", "js-yaml"),
  path.join(os.homedir(), ".dsh", "profiles", "web", "node_modules", "js-yaml"),
].filter(Boolean);
const yaml = require(YAML_PATHS.find((p) => fs.existsSync(p)));

const DSH_HOME = process.env.DSH_HOME || path.join(os.homedir(), ".dsh");
const creds = yaml.load(
  fs.readFileSync(path.join(DSH_HOME, ".credentials.yaml"), "utf8"),
);
const record = creds.records["client-connection/browser-session"];
if (record?.kind !== "grant" || record.payload?.version !== 1)
  throw new Error("unexpected browser-session credential record");
const secret = Buffer.from(
  record.payload.secret.replaceAll("-", "+").replaceAll("_", "/"),
  "base64",
);

const b64u = (v) =>
  Buffer.from(v).toString("base64").replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/u, "");
const cookieName = (authority) =>
  "dsh-auth-" + b64u(crypto.createHash("sha256").update(authority).digest());
function mintCookie(authority, maxAgeDays = 30) {
  const issuedAt = Date.now();
  const expiresAt = issuedAt + maxAgeDays * 1440 * 60 * 1e3;
  const body = b64u(
    Buffer.from(
      JSON.stringify({ version: 1, authority, issuedAt, expiresAt }),
      "utf8",
    ),
  );
  const sig = crypto.createHmac("sha256", secret).update(body).digest();
  return `v1.${body}.${b64u(sig)}`;
}

const [hostHeader, pathname, useCookieFlag, portArg, outFile] = process.argv.slice(2);
const port = Number(portArg ?? 19387);
const authority = hostHeader;
const headers = { host: hostHeader };
if (useCookieFlag === "1") {
  headers.cookie = `${cookieName(authority)}=${mintCookie(authority)}`;
}

const req = http.request(
  { host: "127.0.0.1", port, path: pathname, method: "GET", headers },
  (res) => {
    let body = "";
    res.on("data", (c) => (body += c));
    res.on("end", () => {
      console.log(
        `host=${hostHeader} path=${pathname} cookie=${useCookieFlag ?? "0"} -> ${res.statusCode} ${res.headers["content-type"] ?? ""} len=${body.length}`,
      );
      if (outFile) {
        fs.writeFileSync(outFile, body);
        console.log("  body written to " + outFile);
      } else {
        console.log("  body: " + JSON.stringify(body.slice(0, 120)));
      }
    });
  },
);
req.on("error", (e) => {
  console.log(`host=${hostHeader} path=${pathname} -> ERROR ${e.message}`);
});
req.end();
