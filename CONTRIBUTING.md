# Contributing

## Build

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew assembleDebug
```

Requirements: JDK 17, Android SDK 35, Gradle 8.7 (wrapper included).

## Style

- Kotlin, Jetpack Compose, Material 3.
- Keep the tracker API client (`net/BatchApi.kt`) in sync with BatchDaddy's
  `docs/API.md`.
- Do not add tracker, payout, or treasury-key code here. NPU King is the worker.
- Escape or length-limit any server-provided text before showing it in the UI.
- Keep the beta compute path clearly labelled; do not imply real NPU inference.

## Pull requests

1. Describe the user-visible change and the protocol impact.
2. Build the debug APK and include the SHA-256 in the PR.
3. Test standalone mode and, if possible, a local BatchDaddy tracker.
4. Do not commit `local.properties`, `.gradle/`, `build/`, APKs, keystores, or
   tokens.

## Scope

NPU King owns the Android app: NPU detection, worker service, settings, and the
standalone probe. The tracker, marketplace, company coins, and payout accounting
live in [BatchDaddy](https://github.com/tehNate/batch-daddy).
