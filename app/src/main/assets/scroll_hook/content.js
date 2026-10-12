// Reports scroll position to the host app so pull-to-refresh can gate on
// the real top of the page instead of the raw touch Y coordinate.
//
// Called from every frame of every page. Debounced with requestAnimationFrame
// so we don't spam the native side on every scroll pixel.
(function () {
  if (window.__sgScrollHooked) return;
  window.__sgScrollHooked = true;

  var lastSent = null;
  var pending = false;

  // Walk up the composed tree from the touch target and return the deepest
  // element that actually scrolls. Falls back to the document scroller.
  function findScroller(el) {
    var c = el;
    while (c && c.nodeType === 1) {
      var s = null;
      try { s = getComputedStyle(c); } catch (e) { s = null; }
      if (s && (s.overflowY === 'auto' || s.overflowY === 'scroll') &&
          c.scrollHeight - c.clientHeight > 4) {
        return c;
      }
      c = c.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  // Returns true only if BOTH the deepest inner scroller AND the document
  // root are at the top. That's what we want: pull-to-refresh should only
  // fire when the user has scrolled all the way up in the whole page.
  function compute(ev) {
    var target = null;
    if (ev) {
      if (ev.composedPath && ev.composedPath().length) {
        target = ev.composedPath()[0];
      } else {
        target = ev.target;
      }
    }
    if (!target || target.nodeType !== 1) {
      target = document.body || document.documentElement;
    }
    var inner = findScroller(target);
    var root = document.scrollingElement || document.documentElement;
    return (inner.scrollTop <= 1) && (root.scrollTop <= 1);
  }

  function send(atTop) {
    if (atTop === lastSent) return;
    lastSent = atTop;
    try {
      var p = browser.runtime.sendNativeMessage('SpoonScroll', { atTop: atTop });
      if (p && p.catch) p.catch(function () {});
    } catch (e) {
      // Older GeckoView may not have nativeMessaging on content scripts.
      // Swallow and move on — the flag stays at its last value.
    }
  }

  function report(ev) {
    if (pending) return;
    pending = true;
    var target = ev;
    requestAnimationFrame(function () {
      pending = false;
      try { send(compute(target)); } catch (e) {}
    });
  }

  document.addEventListener('scroll', report, { capture: true, passive: true });
  window.addEventListener('scroll', report, { capture: true, passive: true });
  document.addEventListener('touchstart', report, { capture: true, passive: true });
  document.addEventListener('touchmove', report, { capture: true, passive: true });
  window.addEventListener('resize', report, { capture: true, passive: true });
  document.addEventListener('DOMContentLoaded', function () { report(null); });
  window.addEventListener('load', function () { report(null); });

  // First report as soon as we're injected.
  report(null);
})();
