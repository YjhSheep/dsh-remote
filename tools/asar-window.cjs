#!/usr/bin/env node
// Print a window of text around a needle inside a (huge, one-line) file such as
// app.asar, without loading the whole file. Scans in 16 MiB chunks and keeps a
// needle-length carry so matches spanning a chunk boundary are still found.
//
//   node tools/asar-window.cjs <file> <needle> [chars] [startOffset]
//
// Prints `chars` (default 3000) characters starting at the first match at or
// after startOffset, so a long minified line can be walked in pieces.
"use strict";
const fs = require("fs");

const [file, needle, charsArg, fromArg] = process.argv.slice(2);
if (!file || !needle) {
  console.error("usage: node tools/asar-window.cjs <file> <needle> [chars] [fromOffset]");
  process.exit(2);
}
const want = Number(charsArg || 3000);
const from = Number(fromArg || 0);
const CHUNK = 16 * 1024 * 1024;
const fd = fs.openSync(file, "r");
const buf = Buffer.alloc(CHUNK);

let pos = from;
let carry = "";
for (;;) {
  const read = fs.readSync(fd, buf, 0, CHUNK, pos);
  if (!read) {
    console.error("needle not found after offset " + from);
    process.exit(1);
  }
  const text = carry + buf.toString("latin1", 0, read);
  const hit = text.indexOf(needle);
  if (hit >= 0) {
    const start = pos - carry.length + hit;
    const out = Buffer.alloc(want);
    const got = fs.readSync(fd, out, 0, want, start);
    process.stdout.write(out.toString("latin1", 0, got));
    break;
  }
  carry = text.slice(-needle.length);
  pos += read;
}
