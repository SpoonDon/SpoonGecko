const BLOCKED_URLS = [
  "*://*.doubleclick.net/*",
  "*://*.googlesyndication.com/*",
  "*://*.google-analytics.com/*",
  "*://*.scorecardresearch.com/*"
];

browser.webRequest.onBeforeRequest.addListener(
  function (details) {
    return { cancel: true };
  },
  { urls: BLOCKED_URLS },
  ["blocking"]
);
