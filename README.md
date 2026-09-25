# Nimo — Voice Companion App (Android)

Nimo is a voice-first Android assistant built for elderly users. It shows a
Google Assistant-style overlay panel that can appear over any other app,
supports both voice ("Ok Nimo" wake word) and typed input, and stays out of
the way of whatever app is running underneath it.

Built by **Team Navindus** for SIH 2026.

---

## Tech stack

- **Pure native Android** — Kotlin + Jetpack Compose (no React Native / Expo)
- **Android's built-in `SpeechRecognizer`** for wake-word detection (free,
  no API key, needs internet)
- **`WindowManager` overlay** for the floating panel (same mechanism apps
  like Messenger and Grammarly use)

---

## Prerequisites

| Requirement | Notes |
|---|---|
| **Android Studio** (latest stable) | Also installs the Android SDK automatically |
| **A JDK 17–21** | Some very new bundled JDKs (24/25) break native CMake build steps used by a few dependencies. If your build fails with `WARNING: A restricted method in java.lang.System has been called`, see [Troubleshooting](#troubleshooting) below. |
| **An Android device or emulator** running **API 24+** | Real device recommended — emulators don't pass through your PC's real microphone by default (see below) |

---

## Setup

1. **Clone the repo**
   ```
   git clone <your-repo-url>
   cd <repo-folder>
   ```

2. **Open in Android Studio**
   File → Open → select the project folder → let Gradle sync finish
   (first sync can take several minutes).

3. **Run it**
   Click the green ▶️ Run button, or from a terminal:
   ```
   Run app > select your device/emulator
   ```

That's it — no `npm install`, no Expo, no extra CLI setup. It's a standard
Android Studio project.

---

## Permissions the app needs

On first launch, the app will ask for two permissions — both require manual
action from the user (Android doesn't allow silently granting either):

1. **Microphone** (`RECORD_AUDIO`) — standard runtime permission popup.
2. **Draw over other apps** (`SYSTEM_ALERT_WINDOW`) — opens a system Settings
   screen where the user must flip a toggle. This is required for the
   floating overlay panel to work, and is the same permission Messenger/
   Grammarly-style apps need.

If you deny either, the corresponding feature (voice wake-word / overlay)
won't work until you grant it from **Settings → Apps → Nimo → Permissions**.

---

## How the overlay + wake word work

- `WakeWordManager` listens continuously using Android's `SpeechRecognizer`
  for the phrases "ok nimo", "hello nimo", or "hey nimo". When detected, it
  shows the overlay panel.
- `OverlayService` draws the floating panel using `WindowManager`. The panel
  sits at the bottom of the screen; everything above/around it stays fully
  visible and interactive — only the panel itself intercepts touches.
- Tapping the input field inside the panel opens `QueryInputActivity`, a
  tiny transparent activity used only to reliably show the keyboard (a
  Service-owned overlay window can't reliably take keyboard focus on all
  Android versions — this is the same workaround real chat-head apps use).
- `OverlayController` is the simple API used from `MainActivity` (or
  anywhere else) to show/hide the overlay and check/request the "draw over
  other apps" permission.

**Note:** wake-word detection here uses Google's cloud speech recognition,
so it requires an active internet connection and works most reliably while
the app is in the foreground. If offline/always-on background detection
becomes a hard requirement later, swapping in **Vosk** (fully offline, free,
no account) is the natural upgrade path — ask in the repo issues if you want
that swapped in.

---

## Project structure

```
app/src/main/java/com/example/myapp/
├── MainActivity.kt              Entry point; requests permissions, wires callbacks
├── overlay/
│   ├── OverlayService.kt        Draws the floating panel (WindowManager)
│   ├── OverlayController.kt     show()/hide()/permission helpers
│   ├── WakeWordManager.kt       "Ok Nimo" detection via SpeechRecognizer
│   └── QueryInputActivity.kt    Transparent activity for reliable text input
├── ui/theme/                    Compose theme
└── res/values/themes.xml        Includes Theme.Transparent for the input activity
```

---

## Troubleshooting

**Build fails with `WARNING: A restricted method in java.lang.System has
been called` on a `configureCMakeDebug` task**
Your JDK is too new (24/25). Either:
- Install JDK 17 and point Gradle at it via `android/gradle.properties`:
  ```
  org.gradle.java.home=C:\\path\\to\\jdk-17
  ```
- Or use whatever JDK is bundled with a slightly older Android Studio release.

**"App keeps stopping" on launch**
Check Logcat (View → Tool Windows → Logcat, filter by `AndroidRuntime`) for
the `FATAL EXCEPTION` block and check it against recent changes — most
common cause during development is a leftover reference to a removed
dependency (e.g. Picovoice/Vosk classes after switching to
`SpeechRecognizer`).

**Wake word never triggers, but everything else works**
If you're on an **emulator**, its virtual microphone does not use your PC's
real mic by default. Open the emulator's **Extended Controls → Microphone**
and enable **"Virtual microphone uses host audio input."** On a real device,
just confirm mic permission is granted and you have an internet connection.

**Overlay doesn't appear at all**
Confirm "draw over other apps" permission is granted:
Settings → Apps → Nimo → Advanced → "Display over other apps" → enabled.

---

## Contributing

This is a hackathon project (SIH 2026) — if you're joining the team:
1. Pull the repo, open in Android Studio, sync Gradle, run.
2. No environment variables or secret keys are required to get a basic
   build running, since wake-word detection here uses Android's built-in
   `SpeechRecognizer` (no third-party account needed).
3. If a future contributor adds Vosk or Porcupine back in, remember to keep
   any API keys/model files out of git (see `.gitignore`) and document the
   setup steps here.