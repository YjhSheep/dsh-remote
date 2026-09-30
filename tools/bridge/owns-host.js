// Make DSH's client treat this page as host-owned.
//
// The client derives `isLoopback` (app.asar @370827) from
//   transport?.ownsHost === true || pageLocation === void 0 ||
//   isLoopbackHostname(pageLocation.hostname)
// and off that flag a non-loopback page is deliberately given in-memory settings
// (persistence = isLoopback ? "host" : "memory", @489680) and no settings document
// (@478632). That is why over the LAN the Models page can only report
// "settings are unavailable in this browser" and settings edits never reach the host.
//
// Nothing on the web path ever sets `ownsHost`, and its only two consumers are
// those settings surfaces, so claiming it here restores the phone's settings UI —
// at the price of giving a remote page the same settings read/write the loopback
// page has. That is a deliberate bend of DSH's browser trust boundary; delete this
// file (the bridge skips a missing tag) to fall back to stock behaviour.
//
// Not deferred on purpose: it must be installed before any module script runs.
(() => {
  const KEY = "__DSH_TRANSPORT__";
  const claim = (v) => {
    if (v && typeof v === "object") {
      try {
        v.ownsHost = true;
      } catch {}
    }
    return v;
  };
  // The web page sets no transport at all, so one has to be fabricated; every
  // other reader of this global only probes optional fields (`rpc`, `fetch`,
  // `openStream`, `streamBaseUrl`) behind `??` fallbacks, so an object carrying
  // just `ownsHost` is inert everywhere except the isLoopback test.
  let real = claim(globalThis[KEY]);
  const ensure = () => (real ??= { ownsHost: true });
  try {
    Object.defineProperty(globalThis, KEY, {
      configurable: true,
      enumerable: true,
      get: () => ensure(),
      set: (v) => {
        real = claim(v);
      },
    });
  } catch {}
})();
