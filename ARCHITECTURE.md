# Flow v5.4.0 — System Architecture & Technical Specifications

> **Target Version**: Flow v5.4.0 (Build 30)  
> **Status**: Production Architecture  
> **Operating System**: Android 8.0+ (API 26–35)

---

## 1. Golden Invariants (NEVER Break)

- **I1 (Zero-Loss Audio Vault)**: Audio must be written to `filesDir/voice_vault/rec_${id}.wav` **before** any network request begins. If the network fails, audio remains recoverable.
- **I2 (Transcript Persistence)**: The raw unformatted transcript must be written to `HistoryManager` **before** LLM formatting begins.
- **I3 (Safe Fallback Injection)**: If formatting times out or fails validation, the raw transcript is injected/copied immediately. Spoken words are never dropped.
- **I4 (Zero Slicing Overhead)**: Zero cloud HTTP requests during audio recording. Live preview text is enabled only for true WebSocket streaming or on-device STT; otherwise, the local 60fps waveform + timer runs.
- **I5 (Sub-300ms Circuit Breaker)**: Dead model IDs (404/410) are blacklisted for 24h. Repeated failures (3 consecutive) open a 2-minute circuit breaker. Rate limits (429) enforce `Retry-After` cooldowns without blocking the user.

---

## 2. System Architecture & Component Mapping

```
app/src/main/java/com/flowbangla/app/
├── api/
│   ├── ModelRegistry.kt              # Dynamic model catalog + bundled fallback (models_registry.json)
│   ├── ProviderHealthTracker.kt      # Circuit breaker, 429 cooldown & rolling daily usage tracker
│   ├── SmartProviderRouter.kt        # Strict language-gated ASR waterfall & formatting router
│   ├── TlsPrewarmer.kt               # HTTP/2 TLS & DNS connection pre-warmer on keyboard open
│   ├── SarvamSaarasClient.kt         # Sarvam AI saaras:v3 (mode = "codemix" for Bengali-English)
│   ├── SonioxSttClient.kt            # Soniox stt-async-v5 / stt-rt-v5
│   ├── GeminiInteractionsAdapter.kt  # Google Gemini /v1beta/interactions adapter
│   ├── GroqWhisperClient.kt          # Groq Whisper Large V3 Turbo (English-only)
│   ├── DeepgramNovaClient.kt         # Deepgram Nova-3 Indic speech engine
│   └── DeepgramLiveStreamer.kt       # Real-time WebSocket STT streamer (<150ms)
├── data/
│   ├── PreferencesManager.kt         # EncryptedSharedPreferences (Keystore backed AES256)
│   ├── HistoryManager.kt             # Dictation records & Voice Vault audio retention
│   ├── DictionaryManager.kt          # Personal custom vocabulary words
│   └── SnippetManager.kt             # User shortcut text expansions
├── format/
│   ├── FormattingChain.kt            # Pipeline: Normalizer -> Dictionary -> Snippets -> LLM Polish -> Danda
│   ├── OpenAiCompatibleTextFormatter.kt # Groq gpt-oss polish with reasoning_effort="low"
│   ├── FormatValidator.kt            # Anti-hallucination & brevity verification
│   └── GibberishGuard.kt             # Noise & phantom token rejection
├── service/
│   ├── FloatingBubbleService.kt      # Overlay lifecycle, touch hit-testing, audio capture
│   ├── TextInjectorAccessibilityService.kt # 6-tier insertion (InputConnection, ACTION_PASTE, SET_TEXT)
│   ├── KeyboardStripOverlayController.kt # Pure rounded 46dp x 34dp inline suggestion bar
│   ├── KeyboardVisibilityDetector.kt # Precise TYPE_INPUT_METHOD window geometry tracker
│   └── AudioRecorder.kt              # 16kHz mono PCM WAV recorder with auto-gain control
└── ui/
    ├── MainActivity.kt               # Compose UI, Health Card, Quality & Language segmented controls
    ├── FloatingMicCanvasView.kt      # Titanium-Obsidian & Minimal Ivory Danda-Mic renderer
    ├── HistoryScreen.kt              # History list, Voice Vault playback & multi-provider re-transcription
    └── theme/                        # Ink & Danda theme, Noto Bengali typography (1.75x line height)
```

---

## 3. Language-Gated ASR Waterfall

```
Speech Language Mode:
├── BANGLA / MIXED (Bengali + English code-switching)
│   1. Sarvam AI (saaras:v3, mode = "codemix")  [#1 Bengali accuracy, 6.9% CER]
│   2. Soniox (stt-async-v5)                   [8.1% CER, custom vocab support]
│   3. Gemini 3.5 Transcribe                   [Multimodal audio, free tier]
│   4. ElevenLabs (scribe_v3)                  [Batch audio]
│   5. Deepgram (nova-3)                       [Fast Indic speech]
│   6. Android On-Device STT                   [100% offline fallback]
│   STRICT INVARIANT: NEVER route Bangla/Mixed audio to Groq Whisper!
│
└── ENGLISH (Pure English)
    1. Groq whisper-large-v3-turbo             [~180ms ultra-low latency]
    2. Deepgram (nova-3)
    3. Soniox (stt-async-v5)
    4. Gemini 3.5 Transcribe
    5. ElevenLabs (scribe_v3)
    6. Sarvam AI
```

---

## 4. Polish & Formatting Architecture

1. **Deterministic Fast Path (< 5ms)**:
   - NFC Unicode normalization.
   - User dictionary replacement & personal snippets expansion.
   - Local backtrack command resolution ("delete last sentence", "মুছে ফেলো").
   - Native Bengali Danda punctuation (`।`) injection.
2. **Selective Cloud LLM Polish**:
   - Triggered only when text lacks punctuation, contains hesitation fillers, or requests document structure (e.g. lists, emails).
   - Endpoints: Gemini 3.5 Flash-Lite, Groq `openai/gpt-oss-20b` (fast) / `120b` (quality), or Cerebras `gpt-oss-120b`.
   - **Reasoning Guard**: All `gpt-oss` models must receive `"reasoning_effort": "low"` to keep formatting latency strictly under 300ms.
   - **FormatValidator**: Halts output if length drifts > 2.5x, preambles appear, or language translation occurs.

---

## 5. Security & Privacy

- **Android Keystore**: API keys stored in `EncryptedSharedPreferences` (AES256-GCM values, AES256-SIV keys).
- **Privacy Mode**: When enabled, skips Gemini free tier and utilizes zero-retention enterprise endpoints only.
- **Cloud Backup Exclusion**: `android:allowBackup="false"` in `AndroidManifest.xml` prevents private transcripts and Voice Vault audio from uploading to Google Cloud device backups.
- **Voice Vault Retention**: Configurable to `Last 3` (default), `24 hours`, or `Never`.

---

## 6. Build & Test Commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
Output APK: `app/build/outputs/apk/debug/app-debug.apk`  
Mirror targets: `D:\Projects\Bevel for Android\Flow-v5.0.0.apk`, `Flow-v5.0.0.apk`.
