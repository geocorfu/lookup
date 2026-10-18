# Look Up — project notes for Claude Code

Look Up is a personal, sideloaded Android app that blocks the phone with a full-screen "Look up" screen while the user walks, and releases it after 10 s of standing still. It never blocks in a vehicle, inside an exclusion zone, or outside the user's active time windows. No network, no backend, no accounts. **SPEC.md is the source of truth for behavior; CLAUDE_CODE_GUIDE.md is the build plan.**

## Commands (run from repo root)
- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew test`
- Device check (always first, must show exactly one device): `adb devices`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Launch: `adb shell am start -n com.example.lookup/.MainActivity`
- Uninstall (lockout escape): `adb uninstall com.example.lookup`
- Logs: `adb logcat -s LookUp`

## Architecture rules
- `:core` is a pure Kotlin/JVM module: state machine, events, config, active-window logic. No Android imports (enforced by the module having no Android dependency).
- Time is injected into `:core`, never read from the system, so tests are deterministic.
- `:app` holds Android adapters only: foreground service, sensors, geofencing, blocker, UI. Adapters translate platform callbacks into `:core` events.
- Minimal dependencies: Compose + Material3, lifecycle, play-services-location, DataStore, JUnit. Flag any new dependency before adding it.
- minSdk 34, targetSdk/compileSdk latest stable (36/37). applicationId `com.example.lookup` (placeholder).

## Hard requirements
- No escape hatch: no pause, PIN, or timed disable. Settings values are range-limited (steps 5–30, still 5–30 s, radius 100–300 m).
- Schedule and zone edits take effect from the next active window.
- Steps drive walking/still timing; Activity Recognition and GPS speed act as a vehicle veto.
- Block shows whenever the screen is on, including over the lock screen, with "Maps" and "Emergency call" buttons. Incoming calls can be answered.
- On-screen media controls (shade, lock screen) and volume keys must not be usable while blocked.
- Outside active windows the service is fully stopped.

## Safety (until the blocker is confirmed safe)
- Every debug build must be dismissible via adb. Never touch any device other than the single one shown by `adb devices`.
- Keep the phone on USB while testing blocking.

## Learnings per step
### Step 1 — spec review
- Dev device is the Nord (Android 14+), so minSdk 34, not Android 17-only.
- Activity Recognition lags too much to drive timing; steps lead, AR vetoes.
- The settings ranges and the next-window rule for zone edits close escape hatches.
- Shade/lock screen media controls are the top technical risk; Step 7 tests them first.
