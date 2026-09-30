#!/usr/bin/env node
// Print $DSH_HOME/.credentials.yaml with secret-looking tokens redacted.
"use strict";
const fs = require("fs");
const os = require("os");
const path = require("path");
const home = process.env.DSH_HOME || path.join(os.homedir(), ".dsh");
const file = path.join(home, ".credentials.yaml");
const text = fs.readFileSync(file, "utf8");
process.stdout.write(
  text.replace(/[A-Za-z0-9_\-]{20,}/g, (m) => `<redacted:${m.length}>`),
);
