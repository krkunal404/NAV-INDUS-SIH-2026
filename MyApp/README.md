# Nimo — Voice Assistant Companion App (Android)

Nimo is an advanced, voice-first Android AI companion assistant designed for elder care and hands-free phone control. It features a WhatsApp-style multi-color chat interface, offline & online speech recognition, Hindi/English multi-language support, Google Assistant-style floating system overlay, Emergency SOS calling, messaging, health tracking, and alarm management.

Built by **Team Navindus** for SIH 2026.

---

## ✨ Latest Features & Capabilities

### 🎨 1. Vibrant Multi-Color Chat UI (WhatsApp Style)
- **User Messages (Right):** Displayed in Light Orange / Sunset Coral (`#FF8C42`) chat bubbles.
- **Nimo Messages (Left):** Displayed in Dark Slate Royal (`#242A3D`) chat bubbles with Bright Cyan (`#00E5FF`) accent borders.
- **Centered Round Mic Button:** Prominent circular Fresh Emerald Green button (`#00D26A`, 58dp) in the bottom center.
- **Quick Language Switch:** One-tap Amber Golden (`#FF8C42`) toggle button in the header (`EN` / `HI`).

### 🪟 2. Smart Floating System Overlay (Google Assistant Style)
- **In-App View:** Automatically hides when you are inside the main app to prevent clutter.
- **On Top of Other Apps:** Automatically activates over external apps (YouTube, WhatsApp, Settings, Launcher) when you switch away from Nimo.

### 🌐 3. Multilingual Support (English & Hindi)
- **Voice / Text Language Switch:** Say or type *"Nimo switch to Hindi"* (`"हिंदी में बात करो"`) or *"Switch to English"*.
- **Cross-Lingual Understanding:** When in Hindi mode, even if you type or speak in English (e.g. *"open youtube"*), Nimo responds in Hindi: ***"आपके लिए यूट्यूब खोल रहा हूँ।"***
- **Hindi Text-To-Speech:** Automatically switches Android TTS engine to `Locale("hi", "IN")` for Devanagari Hindi text.

### 🎙️ 4. Hybrid Offline & Online Speech Engine
- **Vosk Offline Speech Model:** Uses the offline Vosk model (`assets/models/vosk-model-small-en-us-0.15`) for offline English voice recognition and continuous wake-word detection (*"Hello Nimo"* / *"Ok Nimo"*).
- **Contact-Biased Speech Recognition:** Dynamically passes user contact display names (`READ_CONTACTS`) into Vosk's grammar decoder for 100% accurate contact name recognition (*"Call Sachin"*).
- **Native Hindi Speech Engine:** Uses Android's native `SpeechRecognizer` (`hi-IN`) for Devanagari Hindi speech recognition.

### 🛑 5. Instant Voice Interruption ("Nimo Stop")
- Say ***"Nimo stop"***, ***"stop"***, or ***"ruk jao"*** (*"रुक जाओ"*): Nimo immediately halts TTS audio playback, cancels pending confirmations, and listens for your next command.

### 🚨 6. Emergency SOS Calling
- Say ***"help"***, ***"emergency"***, ***"SOS"***, ***"save me"***, or ***"मदद"***.
- Bypasses all confirmation questions and directly places an emergency call to `112` or your saved SOS contact.

### 📞 7. Interactive Call Confirmation
- Say ***"Call Kunal"***: Nimo searches contacts, displays/speaks *"Found Kunal (+1234567890). Should I call Kunal?"*, and listens for *"Yes"* / *"No"*.

### ⏰ 8. Alarm Creation & Management
- **Create Alarm:** Say ***"Create alarm for tom at 3:30 pm"*** or ***"Set alarm for 7 AM"***.
- **Interactive Prompt:** If time is omitted (***"Create alarm"***), Nimo asks *"What time should I set the alarm for?"* and listens for your response.
- **Cancel Alarms:** Say ***"Cancel all alarms"*** to open alarm management.

### 💬 9. WhatsApp & SMS Message Reading & Sending
- **Read Messages:** Say ***"read message on whatsapp by Adarsh"*** or ***"read SMS"***.
- **Send Messages:** Say ***"send message to Adarsh on WhatsApp saying I will be late"***.

### 🩺 10. Health Tracking & Reminders
- **Log Health Stats:** Say ***"log blood pressure 120 80"*** or ***"log sugar level 110"***.
- **Health Status:** Say ***"what is my health status"*** to hear latest recorded BP and glucose levels.

---

## 🛠️ Tech Stack & Dependencies

- **Language:** Native Kotlin
- **UI Framework:** Jetpack Compose + Material 3
- **Offline Speech Recognition:** `com.alphacephei:vosk-android:0.3.47`
- **Networking:** OkHttp 4.12.0
- **LLM Provider:** Groq API (`llama-3.3-70b-versatile`)
- **Speech Synthesis:** Android `TextToSpeech` (`Locale.US` & `Locale("hi", "IN")`)
- **System Overlay:** `WindowManager` (`TYPE_APPLICATION_OVERLAY`)

---

## 📋 Required Permissions

1. **Microphone (`RECORD_AUDIO`):** Voice query capture & wake-word listening.
2. **Contacts (`READ_CONTACTS`):** Contact lookup and grammar vocabulary biasing for speech recognition.
3. **Phone Calls (`CALL_PHONE`):** Direct phone calls & Emergency SOS.
4. **SMS (`READ_SMS` / `SEND_SMS`):** Reading and sending SMS messages.
5. **Alarms (`SET_ALARM`):** System alarm scheduling.
6. **Draw Over Other Apps (`SYSTEM_ALERT_WINDOW`):** Google Assistant-style floating overlay over other apps.

---

## 🚀 Setup & Installation

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd MyApp
   ```

2. **Set Groq API Key (Optional for AI responses)**
   In `local.properties`, add your Groq API key:
   ```properties
   GROQ_API_KEY=your_groq_api_key_here
   ```
   *(Note: Local rule-based commands for calling, alarms, opening apps, health, and emergency calls work offline even without an API key).*

3. **Open in Android Studio & Run**
   - Open project in Android Studio (Jellyfish / Koala / Ladybug or newer).
   - Sync Gradle project files.
   - Connect a device/emulator running **API 24+**.
   - Click **Run (`Shift + F10`)**.

---

## 📁 Project Structure

```
app/src/main/java/com/example/myapp/
├── MainActivity.kt                  Main Compose Chat UI & Overlay lifecycle manager
├── llm/
│   ├── NimoLLM.kt                   Groq LLM client & local fallback command parser
│   ├── ActionExecutor.kt            Action execution dispatcher (Apps, Web, Calls, Alarms)
│   ├── CallManager.kt               Interactive call confirmation & contact search
│   ├── EmergencyManager.kt          Emergency SOS direct calling
│   ├── AlarmManager.kt              Alarm creation, time parsing, & cancellation
│   ├── MessageManager.kt            WhatsApp & SMS reading and sending
│   └── HealthManager.kt             Blood pressure & glucose logging
├── overlay/
│   ├── OverlayService.kt            WindowManager floating system overlay panel
│   ├── OverlayController.kt         Show/hide overlay & permission request helpers
│   ├── ActiveVoiceInput.kt          Dual speech capture router (Vosk + Native hi-IN)
│   ├── WakeWordManager.kt           Continuous "Hello Nimo" wake-word listener
│   └── QueryInputActivity.kt        Transparent activity for overlay keyboard input
├── speech/
│   ├── VoskVoiceManager.kt          Offline Vosk model unpacker & speech recognizer
│   ├── ContactVocabularySupplier.kt Contact name grammar biasing for Vosk
│   ├── TTSManager.kt                Multilingual Text-To-Speech engine
│   └── LanguageManager.kt           English / Hindi language preference manager
└── ui/
    ├── ChatMessage.kt               Chat bubble data model
    └── theme/                       Compose color & typography theme
```

---

## 👥 Team Navindus (SIH 2026)
Built for Smart India Hackathon (SIH) 2026.
