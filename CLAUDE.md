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
### Step 2 — scaffold
- Toolchain: JDK 17 (Microsoft), SDK in `%LOCALAPPDATA%\Android\Sdk`, Gradle 9.8.1, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00. compileSdk 37, targetSdk 36.
- `sdkmanager` is deprecated; use `cmdline-tools/latest/bin/android.exe --no-metrics sdk install|list`.
- Dev phone is Android 16 / API 36 (OxygenOS 16.0.5, model CPH2621).
- The Anker dock is charge-only; adb runs over Wireless debugging (`adb pair`, then mDNS auto-connects). If `adb devices` shows the phone twice (IP and mDNS name), `adb disconnect <ip:port>` so only one remains; pass `-s <serial>`.
- Wireless adb is not a reliable lockout safety net; use a direct USB data cable before Step 7.
### Step 3 — state machine
- `DetectionStateMachine.handle(event, nowMs)` returns a `Snapshot`; adapters must send `Tick` (~1 s) so timeouts fire without steps.
- Timeouts run before each event, so a late step after a long silence starts a new walk instead of extending the block.
- The still countdown is 10 s measured from the last step; it becomes visible after `stillGapMs` (2 s).
- Suppression (vehicle activity OR speed, zone, outside window) forces IDLE and ignores steps until all clear.
- `stillGapMs` and `candidateResetMs` are guesses; tune them in Step 4 field tests (screen-off step batching may need larger gaps).
### Step 4 — real sensors (pending field test)
- Nord step detector is `oplus pedometer`, non-wakeup only: with screen off, events may be batched or held until wake. Step log lines carry "sensor delay" to measure it.
- FGS type `health` (ACTIVITY_RECOGNITION) for now; `location` gets added with GPS speed in Step 5.
- Field: transitions and 10 s countdown correct; desk 10 min had zero false steps; screen-locked steps arrived live (3�56 ms delay).
- Nord step detector fires ~1 event/s (vs ~1.8 real steps/s), so 10 "steps" is about 10 s of walking. Not yet known if capped or skipping.
- AR reported WALKING ~23 s late and STILL ~2 min late, confirming steps must drive timing.
- Wireless adb: after re-pairing, mDNS can list the old and new endpoints; disconnect the stale one. Log file: `adb exec-out run-as com.example.lookup cat files/lookup.log`.
### Step 5 — vehicle speed (pending bus/car field test)
- `VehicleSpeedDetector` + `VehicleConfig` (core): on ≥20 km/h for 10 s, off <10 km/h (or GPS silent) for 60 s, 10–20 km/h holds state; samples with speed accuracy >3 m/s ignored. All guesses until bus logs.
- GPS (fused, high accuracy, 1 s) runs only when state != IDLE or vehicle active. FGS types health|location (0x108); falls back to health if location refused.
- Step counter total is logged alongside detector events to check the ~1 event/s detector rate.
- Registering the step detector emits one spurious step immediately; harmless (candidate resets after 3 s).
### Step 6 — zones and active windows (pending field test)
- Core: `Schedule`/`TimeWindow` (multi-range, per-day, overnight wrap, merged edges), `StoredSettings` (edits inside a window stay pending until it ends), `Zone` (100–300 m), `ZoneTracker`, `SettingsCodec`.
- App: settings in SharedPreferences (no DataStore needed); `WindowScheduler.sync` starts/stops the service and sets one exact alarm (USE_EXACT_ALARM) at the next edge; boot and reinstall resync.
- Geofences need ACCESS_BACKGROUND_LOCATION ("Allow all the time", onboarding step 4); location FGS type is refused from background without it (falls back to health).
- Default schedule: every day 07:00–23:00.

## Pending field tests
- Step 4.8 + Step 5: bus/car ride (vehicle by speed, no blocking while riding, release ~60 s after getting off).
- Step 4 threshold: compare step counter vs detector totals on a ~1 min walk.
- Step 6: Home zone walk; window starting in 5 min; edit during a window stays pending.

## Resume point (21/10/2026)
- Steps 1-6 done and pushed. Step 7 (blocking) plan presented, awaiting approval; no Step 7 code yet.
- Plan: AccessibilityService + TYPE_ACCESSIBILITY_OVERLAY (Maps + Emergency buttons), shade auto-collapse if the overlay can't cover it, showWhenLocked activity as lock-screen fallback; SYSTEM_ALERT_WINDOW dropped.
- Prototype order: P1 debug-triggered overlay + window logging, P2 media-controls test (lock screen, shade, QS), P3 allow-list Maps/dialer/in-call, P4 volume keys, then onboarding for the accessibility toggle.
- Escapes: `adb uninstall com.example.lookup`, debug broadcast `com.example.lookup.DEBUG_DISMISS`, 2-min auto-off in prototype, Safe Mode.
- Blocker: needs a USB data cable plugged into the PC (21/10 Windows saw no device; cable or dock is charge-only).
