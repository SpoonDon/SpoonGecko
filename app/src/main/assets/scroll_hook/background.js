// Relays scroll-state messages from content scripts to the native app.
//
// Content scripts don't always have sendNativeMessage exposed in GeckoView,
// so they postMessage({ atTop }) here and we forward it to the native side.
// This keeps the content script trivially simple and puts the native-messaging
// call on the background page, where the API is guaranteed to be present.

browser.runtime.onMessage.addListener(function (msg) {
  if (!msg || typeof msg.atTop !== 'boolean') return;
  try {
    var p = browser.runtime.sendNativeMessage('SpoonScroll', { atTop: msg.atTop });
    if (p && p.catch) p.catch(function () {});
  } catch (e) {
    // Nothing to do — the flag stays at its last value.
  }
});
