# Spoon Gecko – GeckoView Browser with RAM persistence

A lightweight browser for Android using Mozilla GeckoView, designed to stay alive in RAM on OEM skins like OneUI and HyperOS.

## Build
Push to `main` and GitHub Actions will produce an APK. Download from Actions → Artifacts.

## Features
- GeckoView engine (stable)
- Foreground service + wake lock for RAM persistence
- No telemetry
- Minimal Material UI

## Local-network HTTP behavior
- Bare local targets (for example `192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`, `localhost`, `127.0.0.1`) default to `http://` to match normal browser LAN behavior.
- Public domains default to `https://`.
- If a user explicitly requests `https://` on a local host and TLS fails, the app falls back to `http://` automatically without a warning-style interstitial.

Security trade-off:
- This app is a general browser and must support user-entered local IP literals over HTTP.
- Android network security config cannot scope cleartext by RFC1918 CIDR ranges, so cleartext is enabled in config while URL routing keeps HTTPS as default for non-local hosts.
