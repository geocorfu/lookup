# Look Up: Claude Code step-by-step prompt guide

Target phone for development: OnePlus Nord CE4 Lite 5G (not the Nothing Phone 3 in SPEC.md). Everything below accounts for that.

## Ground rules for every session
- One spike per session. Run `/clear` between spikes so context stays clean.
- Commit after every step that works (ask Claude Code to do it, or do it yourself).
- Plan first, build second: press Shift+Tab to cycle into plan mode for the planning prompts.
- Models: plan with Opus 5.5 (high effort), build with Sonnet 5.5 (medium effort). Use `/model` to switch. Go back to Opus for the blocking spike (Step 7) and for any bug that survives two attempts.
- Claude Code cannot walk for you. Every spike ends with a short field test you do on the phone, and you report back what happened (paste logcat output or describe it).

## Step 0: Your machine and phone (no Claude Code yet)
1. Install: Git, JDK 17, Android Studio (this brings the Android SDK and `adb`), and Claude Code.
2. Create the GitHub repo (private to start), clone it, and `cd` into it.
3. Copy `SPEC.md` into the repo root.
4. On the Nord: Settings > About > tap Build number 7 times > back to Developer options > enable USB debugging. Plug in via USB and accept the prompt.
5. Check `adb devices` shows exactly one device. Keep the phone plugged in for the first spikes.

Safety net, read this before Step 7: this app is designed to block your phone with no escape hatch. A bug could lock you out of your own phone. From your computer, `adb uninstall <package-name>` always works while USB debugging is on. Booting into Android Safe Mode also disables third-party apps. Keep the phone connected to your computer whenever you test blocking.

## Step 1: Project memory and review of the spec (plan mode, Opus)
Start `claude` in the repo, switch to plan mode, then paste:

```
Read @SPEC.md. This is a personal Android app. Do not write code yet.

Important change: the development device is a OnePlus Nord CE4 Lite 5G (OxygenOS, probably Android 14 or 15), not the Nothing Phone 3 mentioned in the spec. The spec's target of "Android 17 only" must be revised so the app runs on both.

Please:
1. List anything in the spec that is contradictory, risky, or underspecified, especially around Android 14+ restrictions on foreground services, background location, accessibility services, overlays, and sideloaded apps.
2. Propose the minSdk and targetSdk, the module and package structure, and the libraries you would use (keep dependencies minimal).
3. Ask me up to 5 questions you need answered before building. Do not ask about things you can decide sensibly yourself.
4. Suggest the exact edits you would make to SPEC.md to reflect the device change.
```

Answer its questions, approve the edits, then paste:

```
Apply the SPEC.md edits. Then create a CLAUDE.md that is short (under 60 lines): one paragraph on what the app is, the build/test/install commands, the architecture rules (the state machine is pure Kotlin with no Android dependencies), the hard requirements from the spec, and a line saying SPEC.md is the source of truth. Commit both.
```

## Step 2: Scaffold and prove the toolchain works (Sonnet)
```
Create the Android project from the plan: Kotlin, Jetpack Compose, single module, a placeholder applicationId (use com.example.lookup for now, I will rename it later). Show a single screen that says "Look Up".

Then verify end to end:
1. ./gradlew assembleDebug succeeds.
2. Confirm with `adb devices` that only one device is connected, then install the debug build on it and launch it with adb.
3. ./gradlew test runs (add one trivial test so there is something to run).

Fix whatever breaks. Add a .gitignore for Android, and commit.
```
Field test: you see the app on the phone.

## Step 3: Spike 1a, the state machine (pure Kotlin, tests first)
```
Implement the detection state machine from SPEC.md as a pure Kotlin class with no Android imports.

Inputs are events: StepDetected, ActivityChanged(WALKING | STILL | IN_VEHICLE | OTHER), VehicleSpeedDetected(on/off), ZoneChanged(inside/outside), ActiveWindowChanged(active/inactive), and a time tick. Time must be injected (a clock or tick events), never read from the system, so tests are deterministic.

Behavior: IDLE -> WALKING_CANDIDATE -> BLOCKING -> STILL_COUNTDOWN -> IDLE, with a 10-step threshold and a 10-second still countdown, both configurable. Any step resets the countdown. Entering a vehicle, entering a zone, or leaving the active time window releases the block immediately.

Write the unit tests first from scripted event sequences, covering: normal walk and stop, flicker (fewer than 10 steps), a step during the countdown, vehicle during blocking, entering the home zone mid-walk, leaving the active window mid-block. Then implement until they pass. Commit.
```

## Step 4: Spike 1b, real sensors, no blocking yet
```
Wire the state machine to real sensors on the device. Do NOT block anything yet.

- Onboarding screen that requests, in order, with a plain explanation for each: ACTIVITY_RECOGNITION, notification permission, then location.
- A foreground service that feeds the state machine from Activity Recognition transitions and the step detector sensor. Pick the correct foreground service type for Android 14+ and explain your choice.
- A debug screen showing the current state, the last raw activity, step count since the walk started, and the countdown. Also log every event with a timestamp to logcat under a single tag, and to a rolling file I can pull with adb.

Install it on the phone and tell me exactly how to run the field test.
```
Field test (do each, with the debug screen open then locked, and note what happens): walk 30 steps, stop for 10 s, walk again, ride a bus or car, walk on a bus if you can, sit at a desk. Then paste the logcat or the log file back to Claude Code with: "Here is what I did and what the app showed. Tune the thresholds and fix what is wrong."

Things to expect on OnePlus: OxygenOS kills background services aggressively. If detection stops with the screen off, in phone Settings > Apps > the app > Battery, set it to unrestricted and allow auto-launch/background activity. Tell Claude Code what you changed so it can add this to the onboarding screen.

Commit when detection is trustworthy on a real walk.

## Step 5: Vehicle detection tuning
```
Based on the logs from my field tests, tune vehicle detection. The rule is: if vehicle-type movement is detected (IN_VEHICLE or sustained speed above a threshold), release the block immediately and keep it released until the movement stops. Propose the threshold and duration, add tests with realistic event sequences, and make the values constants in one place.
```
Field test: bus ride, car ride, then walk after getting off.

## Step 6: Zones and active time windows
```
Add two features on top of the working detection, each with unit tests and no changes to the blocking yet:

1. Zones: define a zone using "use my current location" plus a radius (default 100 m), stored locally. Use the Geofencing API. Inside any zone, the state machine receives ZoneChanged(inside) and never blocks.
2. Active time windows: multiple ranges per day, per days of the week. Outside the windows the foreground service is fully stopped (no sensors, no geofences). Schedule changes take effect from the next window, not instantly, as in SPEC.md.

Add a simple settings screen for both. Commit.
```
Field test: set Home as a zone and walk around inside it; set a window that starts in 5 minutes and verify nothing runs before it.

## Step 7: Spike 2, real blocking (plan mode, Opus, the risky one)
Start a fresh session with `/clear`, plan mode, Opus 5.5 high:
```
We now build the blocking. Do not write code yet. Read SPEC.md, CLAUDE.md, and the existing service.

Hard requirements: full-screen "Look up" screen; allowed apps are Google Maps and the emergency dialer; music already playing keeps playing but the media controls on the lock screen and in the notification shade must NOT be usable. The block must survive the notification shade, quick settings, system gestures, and the recents screen as far as Android allows.

Research and propose how to achieve this on Android 14/15 on a OnePlus phone, using only an AccessibilityService and overlay options. Compare at least: (a) AccessibilityService detecting the foreground app and launching a full-screen blocker activity, (b) TYPE_ACCESSIBILITY_OVERLAY, (c) SYSTEM_ALERT_WINDOW. For each, state what it can and cannot cover, including the notification shade and media controls, and what permissions or "restricted settings" steps a sideloaded app needs. Be honest about what you are unsure of. Then give me a test matrix I can run on the phone, and a minimal prototype plan that proves or disproves the media controls requirement first.
```
After you approve the plan, switch to Sonnet if you like and paste:
```
Implement the minimal prototype from the plan: a blocker activated by a debug-only button (not by walking yet). Include a debug-only way to dismiss it, which must be removed or compiled out of release builds. Install it and give me the test matrix to run.
```
Field test, with the safety net ready: shade, quick settings, gestures, recents, Maps allowed, emergency dialer allowed, Spotify playing with the lock screen controls, and a reboot. Report each result back honestly, including the failures. Android may not let you fully cover everything. If media controls cannot be covered, tell Claude Code and decide together between options (for example, blocking via an accessibility action that collapses the shade immediately).

Note for sideloaded apps: Android may grey out the accessibility toggle until you open the app's info page and allow restricted settings. Claude Code should add this to the onboarding.

Then connect it to the state machine:
```
Connect the blocker to the state machine: show it on BLOCKING, release it on STILL_COUNTDOWN completing, vehicle, zone, or leaving the active window. Remove the debug-only button but keep a build-type flag so debug builds can still be dismissed with adb. Commit.
```

## Step 8: Music passthrough and allow-list polish
```
Make the allow-list a single config. Verify that audio playing before the block continues during it, that nothing on screen can change or stop it, and that audio focus is not disturbed by the blocker. Add the tests you can add and a manual checklist for the rest.
```

## Step 9: Hardening and the "done" checklist
```
Do a review pass against SPEC.md and list anything missing or fragile: start on boot, process death and restart, permission revoked mid-walk, location off, GPS-less indoor walking, battery usage, low-memory kills, and the lockout risks. Fix the important ones. Update README.md (what it is, how to build, how to install on a device, how to uninstall) and CLAUDE.md with everything we learned.
```
Then walk around Athens for a few days with it, keep notes, and bring the notes back for tuning.

## Habits that save time
- Start each prompt by pointing at files with `@SPEC.md` and `@CLAUDE.md` so Claude Code reads them first.
- After each spike, say: "Update CLAUDE.md with what we learned in this spike (under 10 lines)".
- If a session gets long and wandery, `/clear` and restart with a one-line summary of where you are.
- When something fails on the phone, give Claude Code the raw logs and what you did, not your theory.
- Rename the app at the end: ask Claude Code to change the app name, applicationId and package in one commit once you pick the name.
