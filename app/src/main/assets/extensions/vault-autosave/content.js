(function() {
    if (window.__vaultAutosaveInjected) return;
    window.__vaultAutosaveInjected = true;

    let lastCapture = 0;

    function captureCredentials(form) {
        const now = Date.now();
        if (now - lastCapture < 3000) return;

        try {
            const passField = form.querySelector('input[type="password"]');
            if (!passField || !passField.value) return;

            const userField = form.querySelector(
                'input[type="email"], input[autocomplete="username"], input[name*="user"], input[name*="email"], input[name*="login"], input[id*="user"], input[id*="email"]'
            ) || Array.from(form.querySelectorAll('input[type="text"], input:not([type])')).find(i => i.value);

            const username = userField ? userField.value : '';
            const password = passField.value;

            if (password.length > 0) {
                lastCapture = now;
                const host = window.location.hostname.replace(/^www\./, '');
                
                // Send to native app via WebExtension native messaging
                browser.runtime.sendNativeMessage("vault-autosave@spoongecko.app", {
                    action: "save",
                    host: host,
                    username: username,
                    password: password
                });
            }
        } catch(e) {
            console.error('Vault autosave error:', e);
        }
    }

    function monitorForm(form) {
        if (form.__vaultMonitored) return;
        form.__vaultMonitored = true;
        form.addEventListener('submit', () => captureCredentials(form));
    }

    function init() {
        document.querySelectorAll('form').forEach(monitorForm);

        // Backup for AJAX logins
        document.addEventListener('click', (e) => {
            let el = e.target;
            while (el && el !== document) {
                if (el.tagName === 'BUTTON' && el.type === 'submit' && el.form) {
                    captureCredentials(el.form);
                    return;
                }
                el = el.parentNode;
            }
        }, true);

        // Watch dynamically added forms (SPAs)
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((m) => {
                m.addedNodes.forEach((node) => {
                    if (node.tagName === 'FORM') monitorForm(node);
                    if (node.querySelectorAll) {
                        node.querySelectorAll('form').forEach(monitorForm);
                    }
                });
            });
        });
        observer.observe(document.documentElement, { childList: true, subtree: true });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
