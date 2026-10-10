// SpoonGecko content blocker — minimal demo.
// Blocks a curated list of common tracking/ad domains.
// Extend by adding more patterns to BLOCKED_URLS.

const BLOCKED_URLS = [
  "*://*.doubleclick.net/*",
  "*://*.googlesyndication.com/*",
  "*://*.google-analytics.com/*",
  "*://*.googletagmanager.com/*",
  "*://*.scorecardresearch.com/*",
  "*://*.adnxs.com/*",
  "*://*.criteo.com/*",
  "*://*.taboola.com/*",
  "*://*.outbrain.com/*",
  "*://*.facebook.com/tr/*",
  "*://*.hotjar.com/*",
  "*://*.mixpanel.com/*"
];

browser.webRequest.onBeforeRequest.addListener(
  function (details) {
    // Never block our own internal resource scheme.
    if (details.url && details.url.startsWith("resource://")) {
      return { cancel: false };
    }
    return { cancel: true };
  },
  { urls: BLOCKED_URLS },
  ["blocking"]
);

console.log("SpoonGecko Blocker loaded");
