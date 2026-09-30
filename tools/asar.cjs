#!/usr/bin/env node
// Minimal read-only ASAR accessor for the DSH installation.
// Usage:
//   node tools/asar.cjs ls <dir>
//   node tools/asar.cjs list <regex>
//   node tools/asar.cjs cat <file>
//   node tools/asar.cjs extract <file> <outFile>
//   node tools/asar.cjs extract-dir <prefix> <outDir>
"use strict";
const fs = require("fs");
const path = require("path");

const ASAR = process.env.DSH_ASAR || "E:\\dsh\\resources\\app.asar";

function open() {
  const fd = fs.openSync(ASAR, "r");
  const head = Buffer.alloc(20);
  fs.readSync(fd, head, 0, 20, 0);
  // [0..3] size-pickle payload size, [4..7] header-buffer length,
  // [8..11] header-pickle payload size, [12..15] JSON string length, [16..] JSON
  const headerSize = head.readUInt32LE(4);
  const jsonSize = head.readUInt32LE(12);
  const json = Buffer.alloc(jsonSize);
  fs.readSync(fd, json, 0, jsonSize, 16);
  return { fd, header: JSON.parse(json.toString("utf8")), baseOffset: 8 + headerSize };
}

function walk(node, prefix, out) {
  for (const [name, entry] of Object.entries(node.files || {})) {
    const p = prefix + name;
    if (entry.files) walk(entry, p + "/", out);
    else out.push({ path: p, size: entry.size, offset: entry.offset });
  }
}

function listAll(handle) {
  const out = [];
  walk(handle.header, "", out);
  return out;
}

function readEntry(handle, entry) {
  const buf = Buffer.alloc(entry.size);
  fs.readSync(handle.fd, buf, 0, entry.size, handle.baseOffset + Number(entry.offset));
  return buf;
}

const [cmd, ...args] = process.argv.slice(2);
const handle = open();
const files = listAll(handle);
const exact = new Map(files.map((f) => [f.path, f]));
const dirs = new Set([""]);
for (const f of files) {
  const parts = f.path.split("/");
  for (let i = 1; i < parts.length; i++) dirs.add(parts.slice(0, i).join("/"));
}

try {
  switch (cmd) {
    case "ls": {
      const dir = (args[0] || "").replace(/^\/+|\/+$/g, "");
      for (const d of [...dirs].sort()) {
        if (path.posix.dirname(d) === (dir || ".")) console.log("d " + d);
      }
      for (const f of files) {
        if (path.posix.dirname(f.path) === (dir || ".")) console.log("f " + f.path + "  " + f.size);
      }
      break;
    }
    case "list": {
      const re = new RegExp(args[0] || ".", "i");
      for (const f of files) if (re.test(f.path)) console.log(f.size + "\t" + f.path);
      break;
    }
    case "cat": {
      const e = exact.get(args[0]);
      if (!e) { console.error("not found: " + args[0]); process.exit(2); }
      process.stdout.write(readEntry(handle, e));
      break;
    }
    case "extract": {
      const e = exact.get(args[0]);
      if (!e) { console.error("not found: " + args[0]); process.exit(2); }
      fs.mkdirSync(path.dirname(args[1]), { recursive: true });
      fs.writeFileSync(args[1], readEntry(handle, e));
      console.log("wrote " + args[1] + " (" + e.size + " bytes)");
      break;
    }
    case "extract-dir": {
      const prefix = args[0].replace(/\/$/, "") + "/";
      const outDir = args[1];
      let n = 0;
      for (const f of files) {
        if (!f.path.startsWith(prefix)) continue;
        const dest = path.join(outDir, f.path.slice(prefix.length));
        fs.mkdirSync(path.dirname(dest), { recursive: true });
        fs.writeFileSync(dest, readEntry(handle, f));
        n++;
      }
      console.log("extracted " + n + " files to " + outDir);
      break;
    }
    default:
      console.error("usage: ls|list|cat|extract|extract-dir");
      process.exit(1);
  }
} finally {
  fs.closeSync(handle.fd);
}
