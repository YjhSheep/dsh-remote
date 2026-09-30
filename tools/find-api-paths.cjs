#!/usr/bin/env node
// Pull every /api-ish and ws-ish string out of the served index HTML so the
// bridge's real transport paths can be exercised instead of guessed.
"use strict";
const fs = require("fs");
const path = require("path");
const html = fs.readFileSync(path.join(__dirname, "..", "tmp", "bridge-index.html"), "utf8");
const hits = new Set();
for (const re of [
  /\/api\/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{0,80}/g,
  /wss?:\/\/[^\s"'`\\]{0,80}/g,
  /__DSH_BOOT__[^\n]{0,200}/g,
]) {
  for (const m of html.match(re) ?? []) hits.add(m);
}
console.log(`distinct hits: ${hits.size}`);
for (const h of [...hits].sort()) console.log("  " + h);
