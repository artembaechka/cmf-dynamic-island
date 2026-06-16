# Dynamic Island for CMF Phone 2 Pro

A ColorOS "Fluid Cloud"–style dynamic island for the **CMF Phone 2 Pro** (Nothing OS 3.2 / Android 15). It draws a black pill around the centered punch-hole that expands for notifications and shows a now-playing card with transport controls — implemented as a normal, side-loadable Android app (no root, no system mod).

---

## 1. What I collected from ColorOS Dynamic Island (the spec it's built to match)

ColorOS calls it **Fluid Cloud** (OnePlus/OxygenOS uses the same name). Behaviour observed across ColorOS 14/15:

| Behaviour | ColorOS Fluid Cloud | This app |
|---|---|---|
| Anchor | Wraps the punch-hole camera cutout | Black pill centered on the cutout, position-calibrated |
| Idle | Small pill merged with the camera | Small pill (the camera hole sits inside the black) |
| Live activity | Music, timers, recordings, calls, ride/food status | Now-playing + notifications (live activities are app-dependent) |
| Stacking | Up to 4 concurrent apps | Single item (MVP) — one notification or one media card |
| Tap | Opens the app / expands the card | Tap opens the app |
| Long-press | Expands to a control card (pause/stop) | Long-press expands/collapses; media card shows prev/play-pause/next |
| Animation | Fluid morph between sizes | Spring (Overshoot) width/height morph |

So the design target is: idle pill → expands on event → controls on interaction → collapses back. That's exactly the state machine this app implements (`COLLAPSED → NOTIF / MEDIA → COLLAPSED`).

The one thing a third-party app fundamentally **cannot** copy: the real Fluid Cloud is part of the OS compositor, so the cutout is genuinely "inside" the island. Here, the pill is just black and the camera hole visually merges with it. On an AMOLED panel with true blacks (your phone qualifies) this looks almost identical when calibrated.

---

## 2. How it works (architecture)

Three pieces, all in one process:

- **`NotificationService`** — a `NotificationListenerService`. Once you grant "notification access" it receives every posted notification and tracks the active `MediaSession` (title, art, play state). It publishes events onto `IslandBus`.
- **`IslandService`** — a foreground service that owns a floating overlay window (`TYPE_APPLICATION_OVERLAY`) positioned at top-center. It listens on `IslandBus` and animates the pill between states using `ValueAnimator` on the window's width/height.
- **`MainActivity`** — permissions, start/stop, and calibration sliders that live-update the running island.

```
Notification / Media  ─►  NotificationService  ─►  IslandBus  ─►  IslandService (overlay)
                                                                      ▲
                          MainActivity (calibrate / start / stop) ────┘
```

Key files: `IslandService.kt` (the pill + animation), `NotificationService.kt` (the data tap), `IslandState.kt` (event bus), `Prefs.kt` (calibration), `res/layout/island_view.xml` (the pill UI).

---

## 3. Build it (Android Studio — easiest)

You need **Android Studio** (Ladybug / 2024.2 or newer).

1. Open Android Studio → **File ▸ Open** → select the `DynamicIsland` folder.
2. Let it sync. Android Studio downloads Gradle + the Android SDK (API 35) and generates the Gradle wrapper automatically. Accept any SDK install prompts.
3. Plug in the CMF Phone 2 Pro with **USB debugging on** (Settings ▸ About phone ▸ tap *Build number* 7×, then Developer options ▸ USB debugging).
4. Pick your phone in the device dropdown and press **Run ▶**. It builds, installs and launches.

To produce a shareable APK instead: **Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)**. The debug APK lands in `app/build/outputs/apk/debug/app-debug.apk` — copy that to the phone and open it to install (allow "install from unknown sources").

### Command line (if you prefer)
From the project root, after Android Studio has created the wrapper once:
```
./gradlew assembleDebug
./gradlew installDebug   # with phone connected
```
First run will need internet (Google Maven + Maven Central) to fetch AndroidX/Material.

---

## 4. First-run setup on the phone

Open the app, then in order:

1. **Grant: Display over other apps** → enable for Dynamic Island.
2. **Grant: Notification access** → enable Dynamic Island in the list (this is what feeds media + alerts).
3. Tap **Start island**. The pill appears at the top.

To test: play a song in any app (the media card appears with working prev/play/next), or trigger any notification (it pops, then auto-collapses after ~3.5 s). Tap the pill to open the app; long-press to expand/collapse.

---

## 5. Calibrate to your exact cutout

The CMF Phone 2 Pro is 1080 × 2392 with a **centered** punch-hole. Defaults are close but every unit/screen-protector shifts things a little. With the island running, use the sliders (they apply live):

- **Vertical position** — slide until the pill's center matches the camera hole.
- **Pill width / height (idle)** — shrink until the black just covers the hole with a thin margin.
- **Expanded width** — how wide the notification/media card grows.
- **Corner roundness** — match the camera-hole curvature for the "merged" look.

Start around: vertical ≈ 8, width ≈ 112, height ≈ 30, corner ≈ 22. Nudge from there.

---

## 6. Known limits & next steps

- **Single item only.** Real Fluid Cloud stacks up to 4. To add stacking, keep a list of active `IslandContent` in `IslandService` and render a compact multi-icon collapsed state.
- **No auto-start on boot.** Android 15 blocks starting a `specialUse` foreground service from `BOOT_COMPLETED`, so you reopen the app after a reboot. (A `BOOT_COMPLETED` receiver that only posts a normal notification "tap to re-enable" is the safe workaround.)
- **Live activities** (timers, ride status) depend on the source app exposing them as notifications; generic apps just show their normal notification.
- **OEM battery killers.** On Nothing OS, lock the app in recents and disable battery optimization for it so the overlay service isn't killed.
- Rounded album art, blur, and per-app accent colors are easy polish additions in `island_view.xml` / `showMedia()`.

---

## 7. Use this as a spec to extend (AI-coding prompt)

If you want to keep building with an AI assistant, paste this:

> Extend an Android (Kotlin, minSdk 29, targetSdk 35) "dynamic island" app for the CMF Phone 2 Pro. It uses a `NotificationListenerService` (`NotificationService`) that publishes `IslandContent` (Notification | Media | Idle) onto a singleton `IslandBus`, and a foreground service (`IslandService`) that owns a `TYPE_APPLICATION_OVERLAY` window centered at the top and animates its width/height between COLLAPSED / NOTIF / MEDIA states with an OvershootInterpolator. Calibration values (top offset, pill width/height, expanded width, corner radius, all dp) live in `Prefs` and are applied live via an `ACTION_CALIBRATE` intent. Now add: [your feature — e.g. stacking up to 4 items, a blurred expanded panel, charging/battery live activity, or a quick-reply action].

---

Built for Maaz · package `com.maaz.dynamicisland`
