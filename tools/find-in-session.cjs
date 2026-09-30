#!/usr/bin/env node
// Read this workspace's own zstd-compressed DSH session log and print the
// user/assistant text blocks containing a keyword.
// Usage: node tools/find-in-session.cjs <keyword> [maxHits]
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const SESSION_DIR = path.join(
  process.env.DSH_HOME || path.join(require("os").homedir(), ".dsh"),
  "sessions",
  "--E-ai-dsh~0020desktop-dsh~0020an--",
);
const log = fs
  .readdirSync(SESSION_DIR)
  .map((d) => path.join(SESSION_DIR, d, "session.v4.jsonl.zstd"))
  .filter((p) => fs.existsSync(p))
  .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
if (!log) throw new Error("no session log found in " + SESSION_DIR);
const text = zlib.zstdDecompressSync(fs.readFileSync(log)).toString("utf8");

const kw = process.argv[2];
const maxHits = Number(process.argv[3] ?? 40);
const collectStrings = (v, out) => {
  if (typeof v === "string") out.push(v);
  else if (Array.isArray(v)) v.forEach((x) => collectStrings(x, out));
  else if (v && typeof v === "object")
    for (const [k, x] of Object.entries(v))
      if (["text", "content", "message", "description"].includes(k))
        collectStrings(x, out);
};

let hits = 0;
for (const line of text.split("\n")) {
  if (!line.includes(kw)) continue;
  if (++hits > maxHits) break;
  let rec;
  try {
    rec = JSON.parse(line);
  } catch {
    console.log(`--- raw line (${line.length} ch) ---\n${line.slice(0, 400)}`);
    continue;
  }
  const out = [];
  collectStrings(rec, out);
  const role = rec.role ?? rec.type ?? rec.kind ?? "?";
  console.log(`--- #${hits} role=${role} ---`);
  for (const s of out) if (s.includes(kw)) console.log(s.slice(0, 3000));
}
console.log(`\n[log=${path.basename(path.dirname(log))}] keyword=${JSON.stringify(kw)} hits=${hits}`);
