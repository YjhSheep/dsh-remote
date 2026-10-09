/* Phone-side idle flag for the floating widget's ☰ button: html[data-dsh-widget-idle]
 * is present while the user has not touched the widget for a few seconds, which is
 * what mobile.css fades the button out on.
 *
 * Why it is needed: a touch device has no hover, so the widget pins that button
 * visible for good (its applyMenuBtnHideUI adds dshwv-menu-btn-visible whenever
 * dshwvTouchUI()) and the ☰ ends up parked on top of the conversation. Only touches
 * that land on the widget wake it again — scrolling the page must not, or the button
 * would be back on screen the whole time the user is reading.
 *
 * Delete this file to drop the behaviour: the bridge skips missing tags.
 */
(function () {
  var IDLE_MS = 3000;
  var doc = document.documentElement;
  var timer = 0;

  function wake() {
    doc.removeAttribute("data-dsh-widget-idle");
    clearTimeout(timer);
    timer = setTimeout(function () {
      doc.setAttribute("data-dsh-widget-idle", "1");
    }, IDLE_MS);
  }

  document.addEventListener(
    "pointerdown",
    function (e) {
      var t = e.target;
      if (t && t.closest && t.closest(".dshwv-root")) wake();
    },
    true,
  );

  wake();
})();
