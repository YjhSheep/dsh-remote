/* Phone-side keyboard flag: html[data-dsh-kb] is present while the soft keyboard
 * is up, which is what mobile.css keys the floating widget off.
 *
 * Why not :focus — on Android, dismissing the IME leaves the field focused, so a
 * `body:has(textarea:focus)` rule hid the widget for good: the phone reported the
 * whale never coming back after the keyboard was closed.
 *
 * The signal: the IME shrinks the layout viewport itself (measured on MiuiBrowser
 * and in the app's WebView: innerHeight 700 -> 428 with position:fixed still
 * correct), so a viewport noticeably shorter than the tallest one already seen
 * means the IME is open. Re-calibrate whenever the width changes (first paint,
 * rotation) instead of comparing one orientation against the other.
 *
 * Delete this file to drop the behaviour: the bridge skips missing tags.
 */
(function () {
  var doc = document.documentElement;
  var timer = 0;
  var widest = 0;
  var tallest = 0;

  function measure() {
    var w = window.innerWidth || 0;
    var h = window.innerHeight || 0;
    if (w !== widest) {
      widest = w;
      tallest = h;
    }
    if (h > tallest) tallest = h;
    if (tallest > 0 && h < tallest * 0.75) doc.setAttribute("data-dsh-kb", "1");
    else doc.removeAttribute("data-dsh-kb");
  }

  function soon() {
    clearTimeout(timer);
    timer = setTimeout(measure, 80);
  }

  window.addEventListener("resize", soon, { passive: true });
  window.addEventListener("orientationchange", soon, { passive: true });
  if (window.visualViewport) window.visualViewport.addEventListener("resize", soon, { passive: true });
  measure();
})();

/* Composer autofocus: the client focuses [data-composer-input] itself — its
 * focusDraftEditor() runs on first paint and again whenever a session becomes the
 * active one (stack captured from a sidebar row click). On Android that raises the
 * IME, so tapping a history task popped the keyboard over the conversation before
 * anyone asked to type. Focus nobody asked for is reverted; a tap on the composer
 * still opens the keyboard, because that pointerdown sets the grace window first.
 *
 * Only a tap on the composer arms that window. If it turns out the IME should stay
 * up after tapping 发送 (the client refocusing the draft editor), arm on that button
 * too — a deliberate second call, not a bug in this one.
 *
 * Delete this file to drop the behaviour: the bridge skips missing tags.
 */
(function () {
  var GRACE_MS = 800;
  var SEL = "[data-composer-input]";
  var allowed = 0;

  function box(node) {
    return node && node.closest ? node.closest(SEL) : null;
  }
  function arm(e) {
    if (box(e.target)) allowed = Date.now() + GRACE_MS;
  }

  document.addEventListener("pointerdown", arm, true);
  document.addEventListener("mousedown", arm, true);
  document.addEventListener(
    "focusin",
    function (e) {
      var el = box(e.target);
      if (el && Date.now() >= allowed) el.blur();
    },
    true,
  );

  // The page may have focused it before this script ran (defer runs late).
  var open = box(document.activeElement);
  if (open) open.blur();
})();
