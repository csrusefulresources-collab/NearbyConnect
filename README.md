# Nearby Connect 1.2.0

Android Bluetooth Classic peer-to-peer messaging app. Both phones must have the app installed. One phone selects **Wait for Incoming Connection**; the other makes itself discoverable and connects to it.

## Build

- JDK: 17
- Gradle: 8.13 (pinned in GitHub Actions)
- Android Gradle Plugin: 8.13.2
- Kotlin Android plugin: 2.2.20
- Compile SDK: 36
- Minimum Android: API 23 (Android 6.0)

GitHub Actions workflow: `.github/workflows/nearby-connect-build.yml`. It deliberately invokes the Gradle 8.13 executable installed by `gradle/actions/setup-gradle` and verifies the version before building.

## Features

- Bluetooth Classic device discovery and paired-device listing
- Incoming connection listener and outgoing RFCOMM connection
- Text messages and M4A voice notes
- Experimental low-bitrate PCM Bluetooth audio calling
- Optional SMS sending through the phone SIM (not through Bluetooth)
- Accessible native Android controls and status announcements

## Important limitations

- Bluetooth audio calling is experimental. RFCOMM is not a dedicated real-time audio transport; quality and latency vary by phone and Bluetooth implementation.
- Both devices must use a compatible Nearby Connect build and matching app UUID/protocol.
- The app supports one Bluetooth peer at a time.
- This package has not been built with an Android SDK in the current environment and has not been tested on two physical phones. Run the included GitHub Actions workflow and perform real-device testing before relying on it.
