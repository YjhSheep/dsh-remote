#!/usr/bin/env node
// Render the bridge URL in headless Chrome at phone metrics, optionally click
// something, then save a screenshot and a geometry dump. This is how the phone
// layout gets measured and re-checked without a round trip to the device.
//
// Usage:
//   node tools/phone-shot.cjs <out.png> [--w 400] [--h 700] [--wait 3000]
//        [--click text:继续,settings] [--dump settings|whale|ellipse]
//        [--after 1200] [--eval "<js>"]
"use strict";
const { spawn, execFileSync } = require("child_process");
const http = require("http");
const fs = require("fs");
const os = require("os");
const path = require("path");

const WS_PATH = path.join(
  os.homedir(),
  ".dsh", "profiles", "desktop", "node_modules", "ws",
);
const WebSocket = require(WS_PATH);
const CHROME = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const KEY = fs.readFileSync(path.join(__dirname, ".bridge-key"), "utf8").trim();
const PORT_CDP = 9222;

// ---- page-side helpers (shipped to the page via toString()) -----------------

/** Click the settings button in the left rail. */
function pageClickSettings() {
  const byLabel = document.querySelector(
    '[aria-label="设置"],[aria-label="Settings"],button[title="设置"]',
  );
  const byClass = document.querySelector('._2H3hWW_settingsArea button, ._2H3hWW_footerActions button:last-child');
  const el = byLabel || byClass;
  if (!el) return "no settings button: " + document.querySelectorAll("button").length + " buttons";
  el.click();
  return "clicked " + (el.getAttribute("aria-label") || el.className || el.tagName);
}

/** Click the first element whose exact trimmed text matches. */
function pageTextClick(needle) {
  const el = Array.from(document.querySelectorAll("button,[role='button'],a,div,span")).find(
    (e) => e.textContent.trim() === needle,
  );
  if (!el) return "no element with text " + JSON.stringify(needle);
  el.click();
  return "clicked " + JSON.stringify(needle);
}

/** Describe one element: geometry plus the computed properties that decide layout. */
function pageDescribe(el) {
  const r = el.getBoundingClientRect();
  const c = getComputedStyle(el);
  return {
    tag: el.tagName.toLowerCase(),
    cls: String(el.className || "").slice(0, 160),
    rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
    display: c.display,
    dir: c.flexDirection,
    cols: c.gridTemplateColumns !== "none" ? c.gridTemplateColumns : undefined,
    width: c.width,
    minWidth: c.minWidth,
    maxWidth: c.maxWidth,
    overflow: c.overflow,
    padding: c.padding,
    gap: c.gap !== "normal" ? c.gap : undefined,
  };
}

/** Walk the settings dialog from a nav label outwards, then inwards. */
function pageDumpSettings() {
  const all = Array.from(document.querySelectorAll("body *"));
  const nav = all.find((e) => e.textContent.trim() === "通用设置");
  const out = { found: Boolean(nav), viewport: [innerWidth, innerHeight] };
  if (nav) {
    const chain = [];
    let el = nav;
    while (el && chain.length < 10) {
      chain.push(pageDescribe(el));
      el = el.parentElement;
    }
    out.navChain = chain;
    let d = nav;
    while (d && getComputedStyle(d).position !== "fixed") d = d.parentElement;
    if (d) {
      const level = (node, depth) => {
        const o = pageDescribe(node);
        if (depth > 0)
          o.children = Array.from(node.children).slice(0, 8).map((k) => level(k, depth - 1));
        return o;
      };
      out.dialog = level(d, 2);
    }
  }
  return JSON.stringify(out);
}

/** Test every whale balloon row against the ellipse the SVG actually draws.
 *  The bubble path's main arc is rx=373 ry=232 about (454,247) in the
 *  viewBox 0 0 1026 700, so a row whose corner gives ((x-cx)/rx)^2 +
 *  ((y-cy)/ry)^2 > 1 is painted outside the bubble. */
function pageCheckEllipse() {
  const pop = document.querySelector(".dshwv-pop");
  if (!pop) return JSON.stringify({ found: false });
  const pr = pop.getBoundingClientRect();
  const s = pr.width / 1026; // viewBox units per css px
  const cx = pr.left + 454 * s;
  const cy = pr.top + 247 * s;
  const rx = 373 * s;
  const ry = 232 * s;
  return JSON.stringify({
    found: true,
    pop: [Math.round(pr.x), Math.round(pr.y), Math.round(pr.width), Math.round(pr.height)],
    ellipse: [Math.round(cx - rx), Math.round(cy - ry), Math.round(2 * rx), Math.round(2 * ry)],
    rows: Array.from(pop.querySelectorAll(".dshwv-text, .dshwv-text > *"))
      .filter((el) => getComputedStyle(el).display !== "none")
      .map((el) => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      const v = [
        [r.left, r.top], [r.right, r.top], [r.left, r.bottom], [r.right, r.bottom],
      ].map(([x, y]) => ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2);
      return {
        cls: String(el.className),
        rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
        fontSize: cs.fontSize,
        wrap: cs.whiteSpace,
        maxV: Math.max(...v).toFixed(2),
        inside: Math.max(...v) <= 1,
      };
    }),
  });
}

/** The whale widget's own boxes, plus what sits under the send button. */
function pageDumpWhale() {
  const pick = (sel) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const o = pageDescribe(el);
    o.text = el.textContent.slice(0, 40);
    return o;
  };
  const root = document.querySelector(".dshwv-root");
  return JSON.stringify({
    root: pick(".dshwv-root"),
    img: pick(".dshwv-img"),
    pop: pick(".dshwv-pop"),
    text: pick(".dshwv-text"),
    label: pick(".dshwv-label"),
    amount: pick(".dshwv-amount"),
    menuBtn: pick(".dshwv-menu-btn"),
    hitAtSend: (() => {
      const el = document.elementFromPoint(innerWidth - 40, innerHeight - 40);
      return el ? el.tagName.toLowerCase() + "." + String(el.className).slice(0, 60) : null;
    })(),
    base: root ? getComputedStyle(root).getPropertyValue("--dshw-base") : null,
    unit: root ? getComputedStyle(root).getPropertyValue("--dshw-u") : null,
  });
}

/** Anything poking out of the settings scroll area, plus the "one character per line"
 *  symptom - the two ways this dialog breaks on a narrow screen. */
function pageDumpOverflow() {
  const box = document.querySelector(".wCInkW_options") || document.querySelector(".wCInkW_content");
  if (!box) return JSON.stringify({ found: false });
  const b = box.getBoundingClientRect();
  // An element inside a horizontally scrollable row is content the user can reach by
  // scrolling, not a break - only count boxes that have no such ancestor.
  const scrollingAncestor = (el) => {
    for (let p = el.parentElement; p && p !== box; p = p.parentElement) {
      const ox = getComputedStyle(p).overflowX;
      if (ox === "auto" || ox === "scroll") return true;
    }
    return false;
  };
  const out = [];
  box.querySelectorAll("*").forEach((el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return;
    if (scrollingAncestor(el)) return;
    if (r.right > b.right + 1 || r.left < b.left - 1) {
      out.push({
        c: el.tagName.toLowerCase() + "." + String(el.className || "").slice(0, 60),
        rect: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)],
        over: Math.round(r.right - b.right),
        text: (el.textContent || "").trim().slice(0, 24),
      });
    }
  });
  // Text squeezed into a tall narrow column == the "one character per line" break.
  const narrow = [];
  box.querySelectorAll("*").forEach((el) => {
    if (el.children.length > 0) return; // leaves only: containers are not the symptom
    const r = el.getBoundingClientRect();
    if (r.width > 0 && r.width < 120 && r.height > 40) {
      narrow.push({
        c: el.tagName.toLowerCase() + "." + String(el.className || "").slice(0, 50),
        w: Math.round(r.width),
        h: Math.round(r.height),
        text: (el.textContent || "").trim().slice(0, 20),
      });
    }
  });
  return JSON.stringify({
    found: true,
    box: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
    clientW: box.clientWidth,
    scrollW: box.scrollWidth,
    clientH: box.clientHeight,
    scrollH: box.scrollHeight,
    overflowed: out.length,
    sample: out.slice(0, 6),
    narrowTall: narrow.slice(0, 6),
  });
}

// ---- CDP plumbing ----------------------------------------------------------

const args = process.argv.slice(2);
const out = args[0] || "tmp/phone.png";
const opt = (name, dflt) => {
  const i = args.indexOf("--" + name);
  return i >= 0 ? args[i + 1] : dflt;
};
const W = Number(opt("w", 400));
const H = Number(opt("h", 700));
const WAIT = Number(opt("wait", 3500));
const AFTER = Number(opt("after", 1500));
const CLICK = opt("click", null);
const DUMP = opt("dump", null);
const EVAL = opt("eval", null);
const WATCH = args.includes("--watch");
// Default loopback; pass --url to load the same bridge over the LAN name, where
// the client classifies the page as non-loopback (the phone's situation).
const URL = opt("url", `http://127.0.0.1:3080/?k=${KEY}`);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const getJson = (url) =>
  new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let b = "";
      res.on("data", (c) => (b += c));
      res.on("end", () => resolve(JSON.parse(b)));
    }).on("error", reject);
  });

function connect(url, watch) {
  const ws = new WebSocket(url);
  let id = 0;
  const pending = new Map();
  const urls = new Map();
  ws.on("message", (raw) => {
    const msg = JSON.parse(raw);
    if (watch && msg.method) {
      const p = msg.params || {};
      if (msg.method === "Runtime.consoleAPICalled") {
        console.log(
          "console." + p.type + ": " +
            (p.args || []).map((a) => a.value ?? a.description ?? a.type).join(" ").slice(0, 400),
        );
      } else if (msg.method === "Network.requestWillBeSent") {
        urls.set(p.requestId, p.request.url);
      } else if (msg.method === "Network.loadingFailed") {
        console.log("FAILED " + (urls.get(p.requestId) || p.requestId) + " :: " + p.errorText);
      } else if (msg.method === "Network.responseReceived" && p.response.status >= 400) {
        console.log("HTTP " + p.response.status + " " + p.response.url.slice(0, 200));
      }
    }
    const q = pending.get(msg.id);
    if (q) {
      pending.delete(msg.id);
      msg.error ? q.reject(new Error(JSON.stringify(msg.error))) : q.resolve(msg.result);
    }
  });
  const ready = new Promise((r) => ws.on("open", r));
  const send = (method, params) => {
    const n = ++id;
    ws.send(JSON.stringify({ id: n, method, params: params || {} }));
    return new Promise((resolve, reject) => pending.set(n, { resolve, reject }));
  };
  return { ready, send, close: () => ws.close() };
}

(async () => {
  const profile = path.join(os.tmpdir(), "dsh-phone-shot");
  const chrome = spawn(
    CHROME,
    [
      "--headless=new",
      `--remote-debugging-port=${PORT_CDP}`,
      `--user-data-dir=${profile}`,
      "--no-first-run",
      "--no-default-browser-check",
      "--disable-gpu",
      "--hide-scrollbars",
      `--window-size=${W},${H}`,
      "about:blank",
    ],
    { stdio: "ignore" },
  );
  const kill = () => {
    try {
      execFileSync("taskkill", ["/PID", String(chrome.pid), "/T", "/F"], { stdio: "ignore" });
    } catch {}
  };
  try {
    let target = null;
    for (let i = 0; i < 40 && !target; i++) {
      await sleep(250);
      try {
        const list = await getJson(`http://127.0.0.1:${PORT_CDP}/json/list`);
        target = list.find((t) => t.type === "page");
      } catch {}
    }
    if (!target) throw new Error("no CDP page target");

    const { ready, send, close } = connect(target.webSocketDebuggerUrl, WATCH);
    await ready;
    await send("Page.enable");
    await send("Runtime.enable");
    await send("Network.enable");
    await send("Emulation.setDeviceMetricsOverride", {
      width: W,
      height: H,
      deviceScaleFactor: 3,
      mobile: true,
    });
    await send("Network.setUserAgentOverride", {
      userAgent:
        "Mozilla/5.0 (Linux; Android 14; dsh-phone-shot) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
    });
    await send("Page.navigate", { url: URL });
    await sleep(WAIT);

    for (const step of CLICK ? CLICK.split(",") : []) {
      const expr = step.startsWith("text:")
        ? pageTextClick.toString() + `; pageTextClick(${JSON.stringify(step.slice(5))})`
        : step === "settings"
          ? pageClickSettings.toString() + "; pageClickSettings()"
          : step;
      const r = await send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
      console.log(
        "click: " +
          JSON.stringify(
            r.result?.value ??
              r.exceptionDetails?.exception?.description ??
              r.exceptionDetails?.text,
          ),
      );
      await sleep(AFTER);
    }
    if (EVAL) {
      const r = await send("Runtime.evaluate", { expression: EVAL, returnByValue: true, awaitPromise: true });
      console.log("eval: " + JSON.stringify(r.result?.value ?? r.result));
      await sleep(AFTER);
    }
    if (DUMP) {
      const fn =
        DUMP === "whale"
          ? pageDumpWhale
          : DUMP === "ellipse"
            ? pageCheckEllipse
            : DUMP === "overflow"
              ? pageDumpOverflow
              : pageDumpSettings;
      const r = await send("Runtime.evaluate", {
        expression:
          pageDescribe.toString() + "\n" + fn.toString() + `; ${fn.name}()`,
        returnByValue: true,
      });
      if (r.exceptionDetails)
        console.log("dump error: " + JSON.stringify(r.exceptionDetails.exception?.description));
      console.log("dump: " + r.result?.value);
    }
    const shot = await send("Page.captureScreenshot", { format: "png" });
    fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
    fs.writeFileSync(out, Buffer.from(shot.data, "base64"));
    console.log("wrote " + out);
    close();
  } finally {
    kill();
  }
})().catch((e) => {
  console.error("FAILED " + e.message);
  process.exit(1);
});
