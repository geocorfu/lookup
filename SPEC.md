# Look Up (working title)

An Android app that blocks the phone while you walk, so you actually see the city around you. Stand still for 10 seconds and it unlocks.

## Target
- Devices: Android 14+ (minSdk 34). Development device: OnePlus Nord CE4 Lite 5G (OxygenOS). Secondary: Nothing Phone 3 (Android 17). Personal use, sideloaded, not Play Store.
- targetSdk/compileSdk: latest stable platform available in the SDK (36, or 37 if stable).
- Language/stack: Kotlin, Jetpack Compose. No legacy support below Android 14.
- No backend, no accounts, no network. Everything on-device.

## Core behavior
1. When the user is walking, a full-screen "Look up" screen blocks the phone.
2. The block lifts only after the user has been still for 10 seconds.
3. Walking is considered started after about 10 steps (configurable constant, default 10) to avoid flicker from small movements.
4. Walking while in a vehicle (bus, train, car) must NOT block.
5. No escape hatch. No pause button, no PIN, no "disable for 1 hour". The only intended way out is uninstalling the app.
   - Accepted platform limit: while NOT blocked, Android lets the user disable the accessibility service, force-stop the app, or revoke permissions. A sideloaded app cannot prevent this. While blocked, Settings is not reachable.
6. The block is shown whenever the screen is on while in BLOCKING/STILL_COUNTDOWN, including over the lock screen.

## Allowed while blocked
- Emergency calls (dialer emergency flow). The block screen has an "Emergency call" button.
- Maps (Google Maps). The block screen has a "Maps" button.
- Allow list lives in one config list (package names) so it can be extended; it holds several emergency dialer packages because the package differs per manufacturer.
- Incoming phone calls can be answered via the system call screen.
- Music: if audio is already playing it continues, but the user cannot change or stop it.
  - Scope: on-screen controls (notification shade, lock screen, apps) and volume keys (via accessibility key filtering).
  - Out of scope: Bluetooth/headset buttons and smartwatch controls, which bypass the screen and cannot be intercepted.
  - Expected technique for the shade: collapse it immediately via accessibility. This may leave a short window (a few hundred ms); Spike 2 measures it.

## Zones
- User can define exclusion zones (Home, Work, more later) using "use my current location" plus a radius (default 100 m, allowed 100–300 m).
- Inside a zone, no blocking even when walking.
- Use the Geofencing API for enter/exit; keep it battery-friendly. Requires "Allow all the time" location. Enter/exit can lag by minutes.
- Zone edits take effect from the next active window, not instantly (same rule as schedules).

## Detection logic (state machine)
States: IDLE -> WALKING_CANDIDATE -> BLOCKING -> STILL_COUNTDOWN -> IDLE
- Inputs: step detector (primary), Activity Recognition transitions (WALKING / ON_FOOT / STILL / IN_VEHICLE, used mainly as a vehicle veto), GPS speed (sampled only in WALKING_CANDIDATE/BLOCKING), geofence state, active window state.
- Rationale: Activity Recognition transitions can lag 30 s to minutes, so steps drive the timing; AR is not required to start walking or to start the countdown.
- IDLE -> WALKING_CANDIDATE: step detected and not in vehicle, not in a zone, inside an active window.
- WALKING_CANDIDATE -> BLOCKING: step count reaches threshold (default 10).
- WALKING_CANDIDATE -> IDLE: no steps for a short gap (constant) before reaching the threshold.
- BLOCKING -> STILL_COUNTDOWN: no steps for a short gap (constant, ~2 s).
- STILL_COUNTDOWN -> IDLE after 10 continuous seconds still; any step resets the countdown (back to BLOCKING).
- Entering IN_VEHICLE, vehicle-type speed, entering a zone, or leaving the active window releases the block immediately.
- Keep the logic in a pure Kotlin module with no Android dependencies so it can be unit tested with fake sensor events. Time is injected, never read from the system.

## Android implementation notes
- Foreground service type `health` (Activity Recognition) plus `location` (GPS speed sampling) keeps detection alive.
- Active windows start the service via exact alarms (USE_EXACT_ALARM or SCHEDULE_EXACT_ALARM), needed to start a foreground service from the background.
- Blocking: start with the simplest approach that works, in this order of preference:
  1. Foreground service + AccessibilityService that detects the foreground app and brings a full-screen blocker activity forward unless the app is on the allow list.
  2. Overlay-only (SYSTEM_ALERT_WINDOW) as a fallback.
- Known risk to test early: whether the notification shade, lock screen media controls, and system gestures can be covered or dismissed on Android 14/15 (OnePlus) and 17 (Nothing). Do not assume.
- Permissions: ACTIVITY_RECOGNITION, fine + background location, notifications, foreground service, exact alarms, accessibility, overlay. Onboarding screen walks through each.
- Sideloaded apps: Android 13+ may grey out the accessibility toggle until "Allow restricted settings" is enabled from App info. Onboarding covers this.
- OxygenOS kills background work aggressively: onboarding guides the user to set battery to Unrestricted and allow auto-launch/background activity.
- Survive reboot (start on boot) and battery optimization (guide user to exempt the app).

## UI
- Blocking screen: minimal, calm, full screen. A short line like "Look up." Optionally a quiet hint when the countdown is running ("Stand still... 7"). Two buttons: "Maps" and "Emergency call".
- Settings: step threshold (5–30), still duration (5–30 s), zones (radius 100–300 m), active windows. Out-of-range values are rejected. Nothing that disables the block.

## Milestones
1. Spike 1: detection only. Screen shows WALKING / STILL / VEHICLE and the 10 s timer. Validate on foot and in a vehicle.
2. Spike 2: real blocking with the allow list (Maps, emergency). Verify shade, lock screen, gestures, recents, media controls.
3. Spike 3: music passthrough, zones, onboarding, boot start.
4. Tuning by real walks in Athens: thresholds, battery usage, false positives.

## Testing
- Unit tests for the state machine with scripted event sequences.
- Manual test checklist: walk, stop 10 s, walk with music, enter home zone, take a bus, make an emergency call, answer an incoming call, reboot, OxygenOS battery settings applied.

## Decisions on earlier open questions
- Music controls on the lock screen / notification shade: they MUST be covered (scope above). This is a hard requirement, not a nice-to-have. Spike 2 must prove it on the development device before anything else is built on top.
- Bus vs. walking on foot: do not rely on walk-vs-bus classification alone. If a vehicle-type movement is detected (Activity Recognition IN_VEHICLE, or sustained speed consistent with a vehicle), that is enough to deactivate the block immediately and keep it off until vehicle movement stops.
- Battery: detection must not run all day. The user sets active time windows (e.g. outside usual sleep and work hours). Outside those windows the service is fully stopped (no sensors, no geofence updates) and no blocking happens. Windows are editable in settings and support multiple ranges and days of the week. They are a schedule, not an escape hatch: schedule changes take effect from the next window, not instantly.

## Open questions
- Exact speed threshold and duration for "vehicle-type movement" (tune in Spike 1 by walking, cycling, and riding a bus in Athens).
- Length of the "no steps" gap that starts the still countdown (tune in Spike 1).
