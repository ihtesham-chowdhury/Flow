# Flow v3.2.0 (AI Voice Dictation & Intelligent Wispr Flow Formatter)

**Flow** is an Android voice dictation app designed as an ultra-fast, unlimited replacement for Wispr Flow. It floats directly above **Gboard** and supports **Bengali (বাংলা), English, and Banglish (code-mixed)** speech with intelligent document-type detection, screen and cursor context awareness, verbal filler removal, and native punctuation (`. , ? ! ""` for English and `। , ? ! ""` for Bengali).

---

## 📱 Pre-Built APK

Download the latest ready-to-install Android package:
* **[Flow-v3.2.0.apk (Latest Release)](https://github.com/ihtesham-chowdhury/Flow/releases/download/v3.2.0/Flow-v3.2.0.apk)**  
  *(Holistic Multi-Window Injection, Decoy Comment Box Resolution, Zero-Loss 1-Tap Recovery Pill, Reactive Permission State)*
* View all releases and previous builds on the **[Releases Page](https://github.com/ihtesham-chowdhury/Flow/releases)**.

---

## 🌟 What's New in v3.2.0 (Multi-Window Injection, Service Persistence & Permission Reactivity)

1. **5-Tier Live Target Node Resolver for YouTube & Facebook Comments**:
   - Solved intermittent injection failures in YouTube comments, Facebook posts/comments, Chrome WebViews, and Meta Litho / React Native views.
   - Decoy non-editable comment boxes are resolved by searching interactive windows (`TYPE_APPLICATION` / `TYPE_INPUT_METHOD` bottom-sheet dialogs) rather than caching stale decoy nodes.
   - Live candidate detection verifies `isEditable`, action capabilities (`ACTION_PASTE`, `ACTION_SET_TEXT`), and known class names (`EditText`, `AutoCompleteTextView`, `WebView`).
2. **4-Tier Resilient Injection Pipeline & Deferred Auto-Paste**:
   - **Tier 1 (Native Paste)**: Executes direct `ACTION_PASTE` on focused input fields.
   - **Tier 2 (Focus + Retry)**: Dispatches `ACTION_FOCUS`, waits 65ms for bottom sheets to mount editors, re-resolves the node, and pastes.
   - **Tier 3 (Hint-Safe Cursor Splicing)**: `TextSpliceHelper` splices at cursor selection without corrupting or duplicating placeholder hint text (`isShowingHintText`).
   - **Tier 4 (Deferred Auto-Paste on Tap)**: If speech ends before a comment box is tapped, text is held on the privileged clipboard with a 45s TTL. The moment the user taps any comment box, Flow pastes immediately!
3. **Zero Word Loss & Bilingual 1-Tap Recovery Pill**:
   - Dictations are permanently backed up in `HistoryManager` and pre-loaded on the system clipboard.
   - If direct injection is deferred or accessibility temporarily unbound, a non-focusable floating pill appears:
     - Bangla: `"📋 লেখাটি কপি হয়েছে — কমেন্ট বক্সে ট্যাপ করলেই বসে যাবে"`
     - English: `"📋 Copied — Tap text box to paste"`
     - Tapping the pill immediately triggers manual paste retry.
4. **Bengali NFC Unicode & Matra Protection**:
   - Injected text is normalized via `Normalizer.normalize(text, Normalizer.Form.NFC)` before injection, preventing split vowel matras (ে, ৈ, ো, ৌ) and hasanta (্) in social media comment feeds.
5. **Overlay Focus Hygiene & Background Persistence**:
   - Overlay layout params strictly include `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH`, ensuring tapping the mic never steals focus from YouTube/Facebook comment bottom sheets.
   - Overrides `onStartCommand` with `START_STICKY` and implements `onTaskRemoved` with an `AlarmManager` 500ms restart intent so closing Flow from Recent Apps never kills the floating mic.
6. **Reactive Permission State in `MainActivity`**:
   - Created `PermissionHelper` checking `instance != null`, `AccessibilityManager.getEnabledAccessibilityServiceList`, and `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
   - Wired to `LifecycleEventObserver(ON_RESUME, ON_START)` and 800ms live polling. Permission warnings clear immediately upon returning from system settings without needing to toggle the UI language.

---

## 🌟 What's New in v3.1.1 (Dictation Fidelity, Zero Leading Space & Dismiss Target)

1. **Strict Anti-Rewriting & Prompt Execution Guard**:
   - Resolved the issue where speaking commands like *"write me the draft email"* caused the LLM to invent an email template with synthetic headers (`To:`, `Subject:`, `Dear Sir`, `Regards`).
   - Flow now treats all dictation as raw speech data: prompts and queries to AI are faithfully transcribed and punctuated verbatim without ever fulfilling, answering, or rewriting the text.
   - `FormatValidator` strictly rejects hallucinated headers, placeholders (`[Your Name]`), or unprompted sign-offs.
2. **Eliminated Stray Leading Space on Text Insertion**:
   - Fixed cursor position detection in `ActiveAppContextExtractor`. When dictating at the beginning of an empty field or new line, Flow strictly sets `needsLeadingSpace = false`.
   - `WisprContextFormatter` trims accidental leading whitespace, ensuring text begins seamlessly flush with the cursor.
3. **Messenger-Style Bottom Drag-to-Dismiss**:
   - Dragging the floating mic down towards the bottom center reveals a circular dismiss target (`✕ Remove`).
   - Dropping the mic inside the target smoothly hides the overlay.
   - Restoring the mic is effortless: tap **[Show Mic / মাইক দেখান]** directly in the ongoing notification, or toggle from the settings screen.
4. **Instant Fast-Path for Short Voices & Speed Optimizations**:
   - Short dictations ($\le$ 7 words) now format locally in **< 1 millisecond**, delivering instant output just like Wispr Flow without waiting for an extra cloud round-trip.
   - Long dictation generation latency is significantly reduced via greedy temperature `0.0` and adaptive token ceilings.

---

## 🌟 What's New in v3.1.0 (Unified Master Architecture & Wispr Flow Context)

1. **Gemini 3.x Dynamic Discovery & Total 404 Purge**:
   - Completely removed all deprecated models (`gemini-1.5-*`, `gemini-2.0-*`, `gemini-2.5-*`, `gemini-pro`, `gemini-3.5-flash` without `-lite`).
   - Implemented `ModelCatalog` with active discovery via `/v1beta/models?pageSize=200` and 24-hr TTL caching.
   - Built to run 100% on active Gemini free-tier endpoints: `gemini-3.5-transcribe` for ASR; `gemini-3.5-flash-lite`, `gemini-3.8-flash`, `gemini-3.6-flash`, and `gemini-3.1-flash-lite` for formatting.
2. **Audio Container Magic Header Sniffing**:
   - Implemented `AudioFileInspector` to validate the first 12 bytes of recordings. Maps MPEG-4 AAC (`ftyp`) strictly to `"audio/m4a"` (never `"audio/mp4"`, resolving Google ASR 400/404 errors), `RIFF WAVE` to `"audio/wav"`, OGG, and MP3.
3. **Google Gemini Interactions API Adapter**:
   - Implemented `GeminiInteractionsAdapter` for modern `/v1beta/interactions` endpoints.
   - Fast-path inline Base64 audio for recordings `< 5 MB`; Resumable Files API (`/upload/v1beta/files`) for recordings `≥ 5 MB` with guaranteed immediate `DELETE` cleanup after transcription.
   - Fully supports post-June 2026 schema (`steps[] -> content[] -> text`).
4. **Wispr Flow Screen & Cursor Context Awareness**:
   - App categorization (`PERSONAL_MESSAGING`, `WORK_MESSAGING`, `EMAIL`, `NOTES_AND_DOCS`, `OTHER`) with security exclusions for banking apps (bKash, Nagad, Rocket, Upay, Cellfin) and password fields.
   - Cursor boundary formatting: seamlessly lowercases the initial letter for mid-sentence dictation continuation and ensures proper leading spacing.
   - Protected token/number validation prevents corruption of currency, numbers, emails, and URLs.
5. **3-Stage Visual Connectivity Lab**:
   - Built-in self-test diagnostics directly in `MainActivity`:
     - **Stage 1 (Key & Models)**: Queries `/v1beta/models` to verify API key validity and capability flags.
     - **Stage 2 (Files API)**: Tests upload and cleanup of a synthetic 1-second audio file.
     - **Stage 3 (Live Interactions)**: Runs live transcription on synthetic test audio.
   - Includes one-tap "Copy Redacted Diagnostics" for instant debugging.
6. **Independent Groq Whisper Fallback with Personal Vocabulary**:
   - Added `GroqWhisperClient` (`whisper-large-v3` / `turbo`) with user-defined custom vocabulary hints.
7. **Safe Bengali Hesitation Filter**:
   - Upgraded `LocalFillerCleaner` with `SAFE_BENGALI_HESITATION_REGEX`, preserving lexical Bengali words (`"এর মানে কী?"`, `"আহা"`, `"এই যে"`).

---

## 🌟 What's New in v2.2.0 (Decoupled ASR & Document-Type Formatting)

1. **Decoupled Speech Recognition from Text Formatting**:
   - In Cloud Audio, speech recognition (ASR) is now completely decoupled from text formatting.
   - **Step 1 (ASR)**: Converts spoken audio (`.m4a`) into an accurate, canonical transcript without fragile formatting prompts.
   - **Step 2 (Format)**: Passes the raw transcript to the document-type formatting engine (`FormattingChain`).
   - **Zero Data Loss Guarantee**: If formatting fails or times out, the raw transcript is **always preserved and inserted**. The user never loses spoken words.
2. **Fixed Cloud Audio "Model not found (404)" and Speech Conversion Failures**:
   - Resolved the 404 issue caused by experimental models (e.g. `gemini-2.0-flash...`) lacking audio modality or missing on `v1` endpoints.
   - Filtered model candidates strictly to verified audio-capable endpoints (`gemini-1.5-flash`, `gemini-2.5-flash`, `gemini-3.5-transcribe`, `gemini-1.5-pro`).
   - Active working endpoints are cached in-memory for sub-second subsequent audio transcriptions.
3. **Independent Groq Whisper Large V3 Cloud ASR**:
   - Added `GroqTranscriptionClient` (`POST https://api.groq.com/openai/v1/audio/transcriptions`) with `whisper-large-v3` and `whisper-large-v3-turbo`.
   - Automatically serves as high-speed fallback or independent cloud ASR when a Groq API key is configured.
4. **Intelligent Document-Type Formatting Engine**:
   - Automatically detects what kind of text the speaker is composing:
     - `TASK_LIST`: Formats actionable items as bullet points (`• Item 1\n• Item 2`).
     - `NUMBERED_STEPS`: Formats sequential steps as `1. Step 1\n2. Step 2`.
     - `EMAIL`: Proper email greeting, body paragraphs, and closing without inventing fake signatures.
     - `CASUAL_MESSAGE`: Direct, concise chat layout for WhatsApp, Messenger, and SMS.
     - `QUESTION_OR_QUERY`: Properly punctuates questions to AI or search engines; **never hallucinates answers**.
5. **Matra-Safe Bengali Typography & Spoken Commands**:
   - Preserves Bengali grapheme clusters (`\u0980-\u09FF`) and NFC Unicode normalization.
   - Cleans hesitation fillers (`উম`, `আহ`, `ইয়ে`, `um`, `uh`) while strictly preserving lexical uses like `"এর মানে কী?"` and expressive `"আহা"`.
   - Spoken commands: "নতুন লাইন" / "new line" (`\n`), "নতুন অনুচ্ছেদ" / "new paragraph" (`\n\n`), "দাঁড়ি" (`।`), "কমা" (`,`), "বুলেট পয়েন্ট" (`• `).
   - 100% unit-tested via JUnit.
6. **Transparent Provenance Tracking**:
   - History and status tags show exact source pairs: `Gemini Transcribe + Gemini Format`, `Groq Whisper + Gemini Format`, `Android STT + Gemini Format`, `Android STT + Local Rules`.

---

## 🛠️ v2.0.2 Fixes & Improvements

1. **Fixed Text Disappearing After Insertion ("Shows Once Then Removes")**:
   - Modern Android notes apps (Xiaomi Notes, Google Keep, Samsung Notes, etc.) use custom text watchers that discard synthetic programmatic text inserted via `ACTION_SET_TEXT`.
   - **Wispr Flow Strategy Implementation**: Flow now pre-loads the formatted text onto the clipboard, focuses the cached target node, and dispatches native `ACTION_PASTE`. Note apps permanently commit native paste events without reverting.
2. **Fixed Gemini Cloud Audio & Polish "Model not found (404)" Error**:
   - Google AI Studio deprecated `gemini-2.0-flash`.
   - Flow dynamically detects active models with fallback loops across both `v1beta` and `v1` REST paths.
3. **Restored Intelligent AI Formatting in Hybrid Turbo**:
   - Full Gemini formatting, capitalization, sentence structuring, and filler removal restored.

---

## 🌟 Engine Modes Overview

### 1. ⚡ Four Flexible Engine Modes
1. **Cloud Audio (Gemini Multimodal / Groq Whisper)**: Captures compressed AAC audio, runs cloud speech recognition, then formats via AI. Best for heavy Banglish code-switching.
2. **Hybrid Turbo (On-Device STT + Cloud Polish)**: Transcribes speech locally on your phone in real-time, then polishes the text in ~200–400ms using a Cloud LLM (Gemini, Groq Llama 3.3 70B, Cerebras Llama 3.1 8B, or OpenRouter).
3. **Offline Lightweight (Zero Download / Zero RAM)**: 100% offline transcription using Android's native speech recognizer + instant local regex filler removal and punctuation heuristics. Consumes 0 MB extra RAM.
4. **Offline AI Model (On-Device LLM)**: 100% offline STT coupled with a downloaded Gemma 3 1B quantized `.litertlm` package using Google LiteRT-LM. Features a 2-minute idle auto-unload to free system RAM.

### 2. 👆 Double-Tap Language Switcher
- In Hybrid or Offline mode, double-tapping the floating mic button while idle instantly toggles between **বাংলা (`bn-BD`)** and **English (`en-US`)** with haptic feedback.
- Anti-aliased vector badge (**`বাং`** or **`EN`**) displayed on the floating button rim.

---

## 🚀 Quick Setup Guide

### 1. Install & Allow Restricted Settings (Android 13 / 14 / 15)
1. Install **Flow-v3.1.0.apk**.
2. Go to phone **Settings** $\to$ **Apps** $\to$ **Manage apps** $\to$ **Flow**.
3. Tap the **3 dots (⋮)** in the top right $\to$ Tap **"Allow restricted settings"** $\to$ Confirm with your phone PIN/fingerprint.
4. Open Flow and turn ON the **Accessibility Service** and **Floating Window Permission**.
5. Set your free Google AI Studio API key (or Groq key) and start dictating!
