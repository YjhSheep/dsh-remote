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
