(function () {
  "use strict";
  if (window.__spoongeckoScrollInstalled) return;
  window.__spoongeckoScrollInstalled = true;

  var NATIVE_APP = "spoongeckoScroll";
  var TOLERANCE = 2;

  var lastAtTop = null;
  var lastSentAt = 0;
  var pending = false;

  function root() {
    return document.scrollingElement || document.documentElement || document.body;
  }

  function topOf(el) {
    return el ? (el.scrollTop || 0) : 0;
  }

  function atTop() {
    var r = root();
    if (!r) return true;
    return topOf(r) <= TOLERANCE;
  }

  function send(atTopVal, y) {
    try {
      browser.runtime.sendNativeMessage(NATIVE_APP, {
        atTop: atTopVal,
        scrollY: y
      });
    } catch (e) {
      // Native side not registered yet.
    }
  }

  function report() {
    var r = root();
    var y = Math.max(0, Math.round(topOf(r)));
    var top = y <= TOLERANCE;

    // Always send on state change; otherwise throttle to 500 ms so a
    // scroll that ends mid-page still refreshes the native value.
    var now = Date.now();
    if (top === lastAtTop && now - lastSentAt < 500) return;

    lastAtTop = top;
    lastSentAt = now;
    send(top, y);
  }

  function schedule() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function () {
      pending = false;
      report();
    });
  }

  // Capture-phase listeners so nested scrollers also trigger a report.
  window.addEventListener("scroll", schedule, { passive: true, capture: true });
  document.addEventListener("scroll", schedule, { passive: true, capture: true });
  window.addEventListener("resize", schedule, { passive: true });
  window.addEventListener("orientationchange", schedule, { passive: true });
  window.addEventListener("load", schedule, { once: true });
  document.addEventListener("DOMContentLoaded", schedule, { once: true });

  // Kick once immediately — pages can load pre-scrolled (back navigation,
  // hash anchors, restored scroll position).
  schedule();
})();
