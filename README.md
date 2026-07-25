# Gecko Browser for Android

A minimal Android browser built with Mozilla's GeckoView engine.

## Features
- Full-featured web rendering via GeckoView
- Address bar with URL entry and navigation controls
- Back/Forward/Refresh buttons
- Page loading progress indicator
- Supports private browsing (disabled by default, can be enabled)

## Build Requirements
- Android Studio Koala or later
- Android SDK 35
- JDK 17

## Getting Started
1. Clone this repository.
2. Open in Android Studio.
3. Sync Gradle and build.
4. Run on an emulator or physical device (API 21+).

## GeckoView Version
This project uses GeckoView 130.0.20240910171355. Update the dependency in `app/build.gradle` if needed.

## Permissions
- Internet access (required)
- Network state (for connectivity checks)

## License
MIT
