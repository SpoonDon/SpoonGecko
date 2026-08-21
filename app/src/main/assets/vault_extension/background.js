browser.runtime.onMessage.addListener((message, sender) => {
    if (message && message.action === "AUTOSAVE_PROMPT") {
        return browser.runtime.sendNativeMessage("spoonvault", message);
    }
});
