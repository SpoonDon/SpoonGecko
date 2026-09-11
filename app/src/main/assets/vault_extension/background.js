browser.runtime.onMessage.addListener((message, sender) => {
  if (!message || !message.action) return;
  return browser.runtime.sendNativeMessage("spoonvault", message);
