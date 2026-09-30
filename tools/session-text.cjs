#!/usr/bin/env node
// DSH appends each turn as its own zstd frame, so zstdDecompressSync only
// returns the first frame. Walk every frame magic and concatenate.
// Usage: node tools/session-text.cjs <out.txt>
"use strict";
const fs = require("fs");
const path = require("path");
const os = require("os");
const zlib = require("zlib");

const SESSION_DIR = path.join(
  process.env.DSH_HOME || path.join(os.homedir(), ".dsh"),
  "sessions",
  "--E-ai-dsh~0020desktop-dsh~0020an--",
);
const log = fs
  .readdirSync(SESSION_DIR)
  .map((d) => path.join(SESSION_DIR, d, "session.v4.jsonl.zstd"))
  .filter((p) => fs.existsSync(p))
  .sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
const buf = fs.readFileSync(log);
const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
const offsets = [];
for (let i = buf.indexOf(MAGIC); i >= 0; i = buf.indexOf(MAGIC, i + 4))
  offsets.push(i);
offsets.push(buf.length);

let text = "";
let ok = 0;
for (let i = 0; i < offsets.length - 1; i++) {
  try {
    text += zlib
      .zstdDecompressSync(buf.subarray(offsets[i], offsets[i + 1]))
      .toString("utf8");
    ok++;
  } catch {
    /* false magic inside compressed payload */
  }
}
const out = process.argv[2] ?? "tmp/session.txt";
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, text);
console.log(
  `log=${path.basename(path.dirname(log))} frames=${offsets.length - 1} decoded=${ok} textBytes=${text.length} -> ${out}`,
);
