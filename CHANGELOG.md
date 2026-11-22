# Changelog

All notable changes to the Flow voice dictation app for Android will be documented in this file.

---

## [5.3.0] - 2026-11-23 (Build 29)
### Added
- **Bottom-Anchored Invariant Inline Fix for Facebook & WhatsApp**:
  - Replaced top-down calibration in `KeyboardVisibilityDetector.kt` with a 2-Tier Invariant Anchor.
  - Tier 1: Direct BFS tree search (max 45 nodes) for Gboard's Voice button or the '1'/'১' number key (`toolbarRowTopY = key1Top - 44dp`).
  - Tier 2: Bottom-Up Invariant Height Anchor measuring upward from `imeRect.bottom` (`(imeRect.bottom - stdBodyHeightPx)`). The distance from `imeRect.bottom` to the `[⊞ ... 🎤]` toolbar row is identical across all apps, guaranteeing that opening Facebook's comment sheet or WhatsApp with an Emoji Fast-Access Bar never shifts the inline mic button onto emojis!
  - Added a `+240ms` settle re-sync in `TextInjectorAccessibilityService.kt` to lock the button cleanly after Facebook bottom-sheet comment modal animations settle.
- **Groq `verbose_json` Automatic Language Detection & Gemini Smart Re-Route**:
  - Implemented `transcribeAudioVerbose` in `GroqWhisperClient.kt` returning detected language, acoustic confidence (`avg_logprob`), and segments in ~180ms.
  - In `SmartProviderRouter.kt`, when speaking Bangla with Smart Model Switching active, Groq detects non-English or low logprob, immediately discards Groq's transcript, and automatically re-transcribes with Gemini (`gemini-3.5-transcribe` / `gemini-3.5-flash-lite`) without requiring manual language toggles!
  - In `FormattingChain.kt`, automatically prioritizes Gemini formatting for Bangla transcripts and Groq formatting for pure English.
- **Zero-Conflict Live Transcription (English, Bangla & Auto)**:
  - Fixed microphone handle contention: `OnDeviceSpeechRecognizer` is never run concurrently while `AudioRecorder` has the hardware microphone open.
  - Priority 1: When a Deepgram API key is saved, live preview connects via `DeepgramLiveStreamer` (<150ms WebSocket streaming) regardless of whether Deepgram is toggled off for final dictation in the 5-provider roster.
  - Priority 2: When no Deepgram key is saved, runs a paced 3.2-second rolling PCM slice loop routed to Groq Whisper Turbo for English or Gemini Flash Lite for Bangla/Auto.
  - Instant 0ms cancellation on Confirm (`✓`) or Cancel (`✕`).

---

## [5.2.0] - 2026-11-22 (Build 28)
### Added
- **Emoji-Bar Adaptive Inline Alignment**:
  - Implemented 2-layer self-calibrating toolbar row anchor in `KeyboardVisibilityDetector.kt` and `TextInjectorAccessibilityService.kt`.
  - Layer A: Direct lookup of Gboard's right-side voice/menu button inside `imeWindow.root` (`key_pos_header_voice_input` / `key_pos_header_access_points_menu`).
  - Layer B: Automatic Emoji-Bar Height-Delta calibration (`baseline_std_ime_height_px`). When Gboard shows its ~44dp Emoji Quick Bar in WhatsApp, Facebook, or Messenger, Flow shifts `toolbarRowTopY` past the emoji row to dock dead-center onto Gboard's `[⊞ ... 🎤]` toolbar row!
- **Smart Automatic Paragraphing (English & Bangla)**:
  - Added `SMART_PARAGRAPH_RULE` to `CLEAN` and `POLISH` modes in `PromptBuilder.kt`. Any speech > 40 words (or 3+ sentences) automatically breaks into clean, scannable 2–4 sentence paragraphs at topic shifts and discourse transitions.
  - In `FormattingChain.kt`, dictations > 30 words in `CLEAN` or `POLISH` mode run through the fast cloud text formatter (Gemini Flash Lite / Groq) to ensure paragraphs are generated for long speech.
- **Human-Readable 3-Tier Formatting Modes**:
  - Replaced ambiguous `Off / Light / Full` with `RAW` (`Raw` / `হুবহু`), `CLEAN` (`Clean` / `পরিষ্কার`, default), and `POLISH` (`Polish` / `নিখুঁত`).
  - Added dynamic 2-line explainer card in `MainActivity.kt` under "Voice and polish" detailing what each level does.
  - In `POLISH` mode, fixes broken grammar/tenses, inserts dropped helper words, and replaces contextually awkward or misheard words.
- **True Zero-Lag Live Transcription**:
  - Created `DeepgramLiveStreamer.kt` connecting directly to `wss://api.deepgram.com/v1/listen` via WebSocket.
  - Added real-time PCM frame streaming callback in `AudioRecorder.kt` (`onPcmFrame`) feeding live binary frames with <150ms interim results without mic contention or extra HTTP polling.
  - Added 3-way **Preview language** selector (`English` | `বাংলা` | `Auto`) directly under the Live Preview switch in `MainActivity.kt`.

---

## [5.1.0] - 2026-11-21 (Build 27)
### Added
- **Crisp Superscript `PAID` Editorial Badges**:
  - Visually distinguished free providers (Gemini, Groq, Deepgram) from paid providers (Sarvam `PAID`, Soniox `PAID`) using `ProviderTitleWithOptionalPaidTag` in the Keys card.
  - Ink & Danda editorial aesthetic with 9.5sp semi-bold typography, subtle Ochre border, and superscript offset.
- **Deepgram Nova-3 Live Dictation Fix**:
  - Replaced filename-extension-based MIME detection with true magic byte inspection via `AudioFileInspector.inspectAndValidate(audioFile).verifiedMimeType`.
  - Added explicit language routing (`language=en` for English, `language=bn` for Bangla, `language=multi` for Mixed) with `model=nova-3&smart_format=true&punctuate=true`, preventing 2-second short clips from hallucinating French/Spanish.
  - Audio flush and disk synchronization (`fos.flush()` and `fos.fd.sync()`) in `AudioRecorder.stopRecordingSafelyIo()` prior to initiating upload.
  - Unified HTTP/2 timeouts across both live recording and history regeneration: `connectTimeout = 8s`, `writeTimeout = 25s`, `readTimeout = 25s`, `callTimeout = 40s`, `retryOnConnectionFailure = true`.
  - Dynamically scaled ASR timeout: `asrTimeoutMs = (14_000L + (durationSec * 600L)).coerceAtMost(90_000L)` and `watchdogMs = asrTimeoutMs + 8_000L`, giving 40s recordings a full 38-second upload and inference budget.

### Removed
- **Purged Clutter Providers**:
  - Permanently cut OpenRouter, ElevenLabs, and Cerebras across entire codebase, UI, preference stores, model catalog, and network routing.
  - Deleted `ElevenLabsScribeClient.kt`.
  - Keys roster streamlined to the 5 verified high-value engines: Gemini, Groq, Deepgram, Sarvam, and Soniox.

---

## [5.0.0] - 2026-11-20 (Build 26)
### Added
- **Top-Ranked Bangla Cloud ASR Engines**:
  - Integrated Sarvam AI `saaras:v3` with native `mode = "codemix"` (#1 benchmarked Bengali-English code-mixed speech recognition, 6.9% CER).
  - Integrated Soniox `stt-async-v5` / `stt-rt-v5` with custom vocabulary support.
- **Model Registry & Circuit Breaker**:
  - `models_registry.json` bundled offline fallback with dynamic remote refresh.
  - `ProviderHealthTracker` with 3-failure 2-minute circuit breaker, 24-hour dead-model blacklist on 404/410, and HTTP 429 `Retry-After` cooldown.
  - JVM unit test (`ModelRegistryTest`) preventing retired models from entering production builds.
- **Strict Language-Gated ASR Waterfall**:
  - Dedicated `SpeechLang` modes (`MIXED`, `BANGLA`, `ENGLISH`).
  - Strict rule: Groq Whisper Large V3 Turbo is restricted to English only (never routed to Bangla or Mixed).
- **HTTP/2 TLS & DNS Pre-Warming**:
  - `TlsPrewarmer` primes top active provider socket on physical keyboard open (`onStartInputView`), saving 300–800ms on 4G networks.
- **UI & UX Polish**:
  - Compact 1-line **Health Card** under Hero block with 1-tap universal connectivity diagnostics.
  - Top-level **Quality** segmented control (`Best` | `Fast` | `Offline`).
  - Top-level **Speech Language** selector (`Mixed` | `বাংলা` | `English`).
  - Re-transcription provider picker in History on Voice Vault audio.
  - Configurable Voice Vault retention (`Last 3` | `24 hours` | `Never`).
  - Privacy Mode toggle (skips Gemini free tier and cloud model training data logging).
  - Minimal Ivory Disc style toggle for the idle floating button.
  - Bengali typography line-height style with `1.75x` spacing and zero edge clipping (`FlowBengaliLineHeightStyle`).
- **Security**:
  - EncryptedSharedPreferences for API keys.
  - Key masking in UI (last 4 characters only).
  - Key prefix validation on paste (`AIza...`, `gsk_...`, `csk-...`, `sk-or-...`, `sk_...`).
  - `android:allowBackup="false"` in `AndroidManifest.xml` preventing dictation history from cloud backups.

### Removed
- Purged all retired Groq and Cerebras models (`llama-3.3-70b-versatile`, `llama-3.1-8b-instant`, `llama-3.3-70b`, `llama3.1-8b`).
- Deleted background cloud-slice HTTP polling loop during recording (saved ~33 requests/min of quota).
- Removed 6-color accent selector in favor of clean Ink & Danda semantics.

---

## [4.5.0] - 2026-11-18 (Build 25)
- Pure rounded stadium pill for keyboard suggestion bar (`46dp x 34dp`, zero square backgrounds).
- Smart language router checking cursor text for Bengali Unicode block `[\u0980-\u09FF]`.
- OpenRouter audio endpoint integration.

---

## [4.4.0] - 2026-11-17 (Build 24)
- Multi-provider toggles and independent API key entry for Gemini, Groq, Cerebras, OpenRouter, ElevenLabs, Deepgram.
- Voice Vault zero-loss audio persistence (`filesDir/voice_vault/`).
- Removed vermilion dot from idle mic button.

---

## [4.3.0] - 2026-11-15 (Build 23)
- Anti-hallucination validation in `FormatValidator`.
- Luxury tactile Obsidian & Brushed Titanium Danda-Mic geometry.
- Live audio preview waveform with 60fps dynamic breathing aura.

---

## [4.2.0] - 2026-11-14 (Build 22)
- Multi-provider key sheets and connectivity lab runner.
- Smart provider routing based on audio duration thresholds.

---

## [4.1.0] - 2026-11-12 (Build 21)
- 6-tier verified text injection (`AccessibilityInputConnection.commitText` -> `ACTION_PASTE` -> `SET_TEXT`).
- Pure vector canvas paths for UI buttons and icons.

---

## [4.0.0] - 2026-11-09 (Build 20)
- Studio inline suggestion bar for Gboard and Samsung Keyboard.
- Live audio amplitude bar animation.

---

## [3.3.1] - 2026-11-09 (Build 18)
- Dynamic model catalog discovery via `/v1beta/models` with 24h caching.
- Resilient IME window top coordinate tracking on portrait and landscape.

---

## [3.1.0] - 2026-10-17
- Decoupled ASR from formatting in Cloud Audio.
- Audio magic byte inspection (`AudioFileInspector.kt`).
- Gemini interactions adapter supporting inline Base64 and resumable Files API.

---

## [2.2.0] - 2026-10-17
- Decoupled speech recognition from document-type layout formatting.
- Added `DocumentType` taxonomy and JSON response envelope.
- Zero data loss guarantee: formatting failure falls back to injecting raw transcript.

---

## [2.0.0] - 2026-10-17
- Re-branded to "Flow".
- 4-engine architecture (Cloud Audio, Hybrid Turbo, Offline Lightweight, Offline Local LLM).
- Hardware-accelerated vector canvas mic overlay.

---

## [1.0.0] - 2026-10-17
- Initial proof-of-concept floating bubble service on Android.
