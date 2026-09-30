// Injected by tools/dsh-lan-bridge.cjs into the top-level DSH document.
// Reports the real layout of whatever device opened the page back to the bridge,
// so mobile adaptation is driven by measurements instead of guesses.
(function () {
  "use strict";
  var slim = function (el) {
    if (!el) return null;
    var b = el.getBoundingClientRect();
    return {
      x: Math.round(b.x),
      y: Math.round(b.y),
      w: Math.round(b.width),
      h: Math.round(b.height),
    };
  };
  var name = function (el) {
    if (!el || el.nodeType !== 1) return String(el);
    return (
      el.tagName.toLowerCase() +
      (el.id ? "#" + el.id : "") +
      (typeof el.className === "string" && el.className.trim()
        ? "." + el.className.trim().split(/\s+/).join(".")
        : "")
    );
  };
  var chain = function (el, stopAt) {
    var out = [];
    while (el && el !== stopAt && out.length < 24) {
      out.push({ el: name(el), rect: slim(el), pos: getComputedStyle(el).position });
      el = el.parentElement;
    }
    return out;
  };
  // env(safe-area-inset-*) is only non-zero when the page opts into them, but
  // measuring still tells us whether the device needs the opt-in at all.
  var inset = function (prop) {
    var d = document.createElement("div");
    d.style.cssText =
      "position:fixed;visibility:hidden;padding-bottom:env(safe-area-inset-bottom);" +
      "padding-top:env(safe-area-inset-top);padding-left:env(safe-area-inset-left);" +
      "padding-right:env(safe-area-inset-right);height:0";
    document.body.appendChild(d);
    var cs = getComputedStyle(d);
    var v = {
      top: cs.paddingTop,
      right: cs.paddingRight,
      bottom: cs.paddingBottom,
      left: cs.paddingLeft,
    }[prop];
    d.remove();
    return v;
  };

  function collect() {
    var vv = window.visualViewport;
    var root = document.querySelector("#root, #app, [data-dsh-root]") || document.body;
    var textarea = document.querySelector("textarea, [contenteditable='true']");
    var corner = document.elementFromPoint(innerWidth - 22, innerHeight - 22);
    var deep = function (el) {
      // deepest element painted at a point: reveal whatever overlay owns it
      var seen = [];
      while (el && seen.length < 12) {
        seen.push({ el: name(el), rect: slim(el), z: getComputedStyle(el).zIndex });
        el = el.firstElementChild;
      }
      return seen;
    };
    return {
      at: new Date().toISOString(),
      url: location.href,
      ua: navigator.userAgent,
      viewport: {
        innerWidth: innerWidth,
        innerHeight: innerHeight,
        dpr: devicePixelRatio,
        screenW: screen.width,
        screenH: screen.height,
        vvWidth: vv && Math.round(vv.width),
        vvHeight: vv && Math.round(vv.height),
        vvOffsetTop: vv && Math.round(vv.offsetTop),
        orientation: screen.orientation && screen.orientation.type,
      },
      document: {
        clientW: document.documentElement.clientWidth,
        clientH: document.documentElement.clientHeight,
        scrollW: document.documentElement.scrollWidth,
        scrollH: document.documentElement.scrollHeight,
      },
      safeArea: {
        top: inset("top"),
        right: inset("right"),
        bottom: inset("bottom"),
        left: inset("left"),
      },
      htmlVars: (function () {
        var cs = getComputedStyle(document.documentElement);
        var out = {};
        [
          "--dsh-frame-overlay-top",
          "--dsh-dockkit-float-top",
          "--dsw-radius-l",
        ].forEach(function (n) {
          out[n] = cs.getPropertyValue(n).trim();
        });
        return out;
      })(),
      root: { el: name(root), rect: slim(root) },
      // Does the mobile patch in tools/bridge/mobile.css actually take effect on
      // this device? Its hide rules are :has()-based and width-gated, and the
      // whale's visibility is the end-to-end proof of the hide-while-typing fix.
      supports: {
        hasSelector: CSS.supports("selector(:has(*))"),
        narrowMedia: matchMedia("(max-width: 900px)").matches,
      },
      whale: (function () {
        var el = document.querySelector(".dshwv-root");
        if (!el) return null;
        var cs = getComputedStyle(el);
        return { el: name(el), rect: slim(el), visibility: cs.visibility, z: cs.zIndex };
      })(),
      rootChildren: [].slice.call(root.children).map(function (el) {
        return {
          el: name(el),
          rect: slim(el),
          pos: getComputedStyle(el).position,
          overflow: getComputedStyle(el).overflow,
        };
      }),
      composerChain: chain(textarea, document.body),
      bottomRightCorner: deep(corner),
      fixedOverlays: [].slice
        .call(document.querySelectorAll("body *"))
        .filter(function (el) {
          var cs = getComputedStyle(el);
          return cs.position === "fixed" && Number(cs.zIndex) >= 10;
        })
        .slice(0, 25)
        .map(function (el) {
          return { el: name(el), rect: slim(el), z: getComputedStyle(el).zIndex };
        }),
      classes: (function () {
        var set = {};
        [].slice
          .call(document.querySelectorAll("body *"))
          .slice(0, 4000)
          .forEach(function (el) {
            if (typeof el.className !== "string") return;
            el.className.trim().split(/\s+/).forEach(function (c) {
              if (c) set[c] = (set[c] || 0) + 1;
            });
          });
        return Object.keys(set)
          .sort(function (a, b) {
            return set[b] - set[a];
          })
          .slice(0, 250);
      })(),
    };
  }

  function report(tag) {
    var payload;
    try {
      payload = collect();
      payload.tag = tag;
    } catch (e) {
      payload = { tag: tag, error: String(e && e.stack ? e.stack : e) };
    }
    try {
      fetch("/__bridge/probe", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload),
      });
    } catch (e) {
      /* the report is best-effort; never break the app */
    }
  }

  if (document.readyState === "complete") setTimeout(report, 1500);
  else addEventListener("load", function () { setTimeout(report, 1500); });
  // the keyboard opening is the layout change that matters most on a phone
  if (window.visualViewport)
    visualViewport.addEventListener("resize", function () {
      clearTimeout(window.__dshProbeT);
      window.__dshProbeT = setTimeout(function () { report("viewport-resize"); }, 600);
    });
  document.addEventListener("focusin", function (e) {
    if (e.target && (e.target.tagName === "TEXTAREA" || e.target.isContentEditable))
      setTimeout(function () { report("focus"); }, 900);
  });
})();
