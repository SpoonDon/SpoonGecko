# Spoon Gecko – GeckoView Browser with RAM persistence

SpoonGecko is a minimal, privacy-first Android browser powered by Mozilla GeckoView. It persists browser sessions in RAM across rotations and supports Firefox-compatible WebExtensions.

---

## Extension Support

SpoonGecko includes first-class support for Firefox-compatible browser extensions (WebExtensions / `.xpi` format) via GeckoView's `WebExtensionController` API.

### Supported browsers / runtime
- **Android** (the app is an Android application)
- Extensions must be compatible with Firefox for Android (GeckoView 153+)
- Manifest V2 and Manifest V3 extensions are both supported by GeckoView

### How to install an extension

**From a local file (side-loading)**
1. Open the app menu (⋮) → **Extensions**
2. Tap **Install .xpi**
3. Select an `.xpi` file from your device storage
4. The extension is installed and enabled immediately

**From Firefox Add-ons (AMO)**
1. Open the app menu (⋮) → **Extensions**
2. Tap **Browse AMO** – opens the Firefox Add-ons catalogue in the current tab
3. Download an `.xpi` from AMO; when the download completes in the **Downloads** screen, re-open Extensions and install it via **Install .xpi**

> **Note:** SpoonGecko currently accepts `content://` (file-picker) and `https://` source URIs only. Plain `http://` install sources are blocked to prevent MITM injection.

### Enable / disable an extension

Each installed extension has a toggle switch. Disabling an extension suspends it without removing it; you can re-enable it at any time. The enabled/disabled state is persisted across app restarts.

### Remove an extension

Tap **Remove** on any extension card. A confirmation dialog is shown before the extension is deleted.

### Permissions used by extensions

Extensions may request additional permissions at install time (GeckoView mediates these). Site permissions (camera, microphone, location) are handled on-demand by Android and GeckoView's `PermissionDelegate`.

---

## Build configuration

### EXTENSIONS_ENABLED flag

You can disable the entire extension feature at build time by setting the `EXTENSIONS_ENABLED` environment variable:

```bash
# Enable (default)
EXTENSIONS_ENABLED=true ./gradlew assembleRelease

# Disable (ships without extension UI)
EXTENSIONS_ENABLED=false ./gradlew assembleRelease
```

When `EXTENSIONS_ENABLED=false`, the Extensions screen shows a short message and no controls are displayed. No GeckoView extension API calls are made.

---

## Architecture

| Component | Purpose |
|---|---|
| `ExtensionController` | Centralised wrapper around `WebExtensionController`; handles install, uninstall, enable, disable, list, source validation, and error formatting |
| `ExtensionsActivity` | UI for extension management (install, toggle, remove, AMO link) |
| `MainActivity` | Hosts the `GeckoRuntime` singleton; owns `GeckoSession` lifecycle |
| `BrowserService` | Foreground service keeping the browser alive in the background |

### Security model

- Extension install accepts only `content://` (file-picker) and `https://` URIs.
- Extensions are run inside GeckoView's process isolation (`fissionEnabled`, `isolatedProcessEnabled`).
- No extension tokens or credentials are stored or logged by the app.
- GeckoView enforces extension signing via Mozilla's AMO signing infrastructure.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "Install failed: extension must be signed" | Extension is not AMO-signed | Use an extension downloaded from addons.mozilla.org |
| "Install failed: extension is not compatible" | Extension targets a different Firefox version | Check the extension's `manifest.json` `gecko.strict_min_version` |
| "Install failed: the .xpi file is corrupted" | Incomplete download / bad file | Re-download the `.xpi` file |
| "Install failed: network error" | Remote install failed | Check network; retry or download and use local .xpi |
| Extension toggle has no effect | GeckoRuntime not ready | Return to the browser and re-open Extensions |
| "Extension support is not available in this build" | Built with `EXTENSIONS_ENABLED=false` | Rebuild with default or `EXTENSIONS_ENABLED=true` |

---

## Development

### Running unit tests

```bash
./gradlew :app:testDebugUnitTest
```

The `ExtensionControllerTest` class validates:
- Source URI allow-list logic
- Install error message formatting
- Null-safety of metadata accessor helpers

### Building

```bash
./gradlew assembleDebug
```

### Release signing

Set the following environment variables before running `assembleRelease`:
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`
