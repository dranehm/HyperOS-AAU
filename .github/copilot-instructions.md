# Copilot Instructions

## Build Commands

```bash
# Debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/

# Release APK (requires a keystore — see signing config below)
./gradlew assembleRelease

# Clean build
./gradlew clean
```

There are no tests in this project.

## Architecture

Single-module Android app (Kotlin + Jetpack Compose). All logic lives in two files:

- **`MainActivity.kt`** — UI only. Hosts the Compose content, requests notification permission on Android 13+, and syncs the `caffeineMode` flag to `FLAG_KEEP_SCREEN_ON` via a `DisposableEffect`.
- **`UnlockViewModel.kt`** — All business logic, state, networking (OkHttp), NTP sync (Apache Commons Net), wake lock management, and notification posting. The ViewModel must be initialized with `viewModel.init(applicationContext)` from `onCreate` before any process can start.

The core flow in `UnlockViewModel.startProcess()`:
1. Validate the cookie against the Xiaomi unlock API.
2. Query `pool.ntp.org` for a clock offset (`ntpOffsetMs`); all time comparisons use `System.currentTimeMillis() + ntpOffsetMs`.
3. Wait until 10 seconds before Beijing midnight, then measure round-trip latency to `sgp-api.buy.mi.com` (average of 5 HEAD requests).
4. Calculate `N` send times (`waves`) spread across a ±60 ms bracket around midnight minus latency.
5. Fire all waves as concurrent coroutines on `Dispatchers.IO`.

## Key Conventions

**State management** — All observable state in `UnlockViewModel` uses Compose `mutableStateOf`/`mutableStateListOf` directly (no `StateFlow`/`LiveData`). UI reads these properties directly from the ViewModel reference.

**Coroutine dispatching** — `startProcess()` runs on `Dispatchers.IO`. UI state mutations that must hit the main thread use `withContext(Dispatchers.Main)`. Log appends go through `viewModelScope.launch(Dispatchers.Main)`.

**Theme colors** — Defined as top-level `val` constants in `MainActivity.kt` (`OrangeMain`, `DarkBackground`, `SurfaceColor`, `TextGray`). Use these; don't add hardcoded color literals.

**Wave result codes** — Decoded in `getResultMeaning()`:
- `1` → slot approved
- `2` → already approved
- `6` → quota full (all slots taken)

**Signing** — The release signing config reads from environment variables (`KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) and falls back to hardcoded defaults. In CI, the keystore is generated ephemerally by the workflow; it is not committed.

**Releases** — Pushing to `main` triggers `.github/workflows/release.yml`, which builds a signed release APK and creates a GitHub Release tagged with the `versionName` from `app/build.gradle.kts`.

## Tech Stack

- **Language/UI**: Kotlin 1.9.23, Jetpack Compose (BOM 2024.02.01), Material3
- **Networking**: OkHttp 4.12.0
- **NTP**: Apache Commons Net 3.10.0
- **Min SDK**: 26 (Android 8.0) | **Target/Compile SDK**: 34
- **Java toolchain**: JVM target 17
