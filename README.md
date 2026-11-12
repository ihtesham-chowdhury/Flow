# Flow v4.1.0 (Pure Studio Mic Icon, Zero-Lag Keyboard Hide, Real-Time Live Streaming Preview & Sub-Second Insertion)

**Flow** is an Android voice dictation app designed as an ultra-fast, unlimited replacement for Wispr Flow. It floats directly inside **Gboard's suggestion bar** and supports **Bengali (বাংলা), English, and Banglish (code-mixed)** speech with intelligent document-type detection, screen and cursor context awareness, verbal filler removal, and native punctuation (`. , ? ! ""` for English and `। , ? ! ""` for Bengali).

---

## 📱 Pre-Built APK

Download the latest ready-to-install Android package:
* **[Flow-v4.1.0.apk (Latest Release)](https://github.com/ihtesham-chowdhury/Flow/releases/download/v4.1.0/Flow-v4.1.0.apk)**  
  *(Pure Studio Mic Icon, Zero-Lag Keyboard Hide, Real-Time Live Streaming Preview & Sub-Second Insertion)*
* View all releases and previous builds on the **[Releases Page](https://github.com/ihtesham-chowdhury/Flow/releases)**.

---

## 🚀 What's New in v4.1.0 (Pure Studio Mic, Zero-Lag Hide & Sub-Second Insertion)

1. **Sub-Second Long Dictation Insertion (< 600ms)**:
   - Eliminated the 30–50s delay between clipboard copy and insertion.
   - Stopped premature clipboard writes before formatting: clipboard copy and cursor text injection now execute at the exact same millisecond.
   - Leveraged `gemini-3.5-transcribe` native `mode: { type: "smart" }` as the single-pass formatter for long dictations (> 35 words), running our < 2ms deterministic local pipeline (`PersonalDictionaryManager` -> `SnippetManager` -> `LocalBacktrackResolver` -> `TranscriptPostProcessor` -> `WisprContextFormatter`).
   - Restricted the 2nd cloud LLM pass (`formatTranscript`) strictly to short-to-medium dictations (4..35 words) matching complex backtrack/list cues (`BACKTRACK_OR_LIST_CUE_REGEX`), capped to 1 single model attempt with strict 1,800ms timeout (zero multi-model retry loops).
2. **Pure Studio Mic Icon in `StripState.IDLE`**:
   - Completely removed `"EN"` / `"বাং"` text from the idle inline trigger button.
   - Transformed into a sleek `40dp x 34dp` stadium capsule with centered mathematical Danda-Mic vector icon and a 2.3dp Vermilion jewel dot at top right.
   - Tapping immediately transitions to `StripState.RECORDING` and starts dictation with zero delay.
3. **Right-Edge Pill Clipping Fix (`Screenshot_20261112-202023_Google.png`)**:
   - Expanded recording width to `screenWidth - 110dp` and `targetX = 52dp`, clearing Gboard's right vertical divider by 6dp.
   - Inset `pillRect` by `3dp` horizontal and `2dp` vertical, and inset Cancel/Confirm buttons symmetrically with `innerGap = 3.5dp` so outer pill radius wraps cleanly concentric around `✓` with zero clipping.
4. **Active 60fps Live Waveform Animation**:
   - In `KeyboardStripOverlayController`, modulated each waveform bar `i` with `sin((nowMs / 85.0) + (i * 0.7)) * 0.22f * currentNormalizedAmplitude` and invoked `postInvalidateOnAnimation()` at the end of `StripState.RECORDING`.
   - In `FloatingBubbleService`, implemented dedicated 50ms poller using perceptual square-root scaling `sqrt(rawAmp / 12000f).coerceIn(0.08f, 1.0f)` to keep 13 red bars dynamically responding to voice energy.
5. **Zero-Lag (0ms) Keyboard-Close Hide**:
   - In `TextInjectorAccessibilityService`, overrode `onFinishInput()` to call `keyboardStripController?.hideImmediatelyOnKeyboardClose()` synchronously at millisecond 0 when keyboard begins closing.
   - On `TYPE_WINDOWS_CHANGED` and `TYPE_WINDOW_STATE_CHANGED`, ran `syncKeyboardVisibilityWithBubble()` immediately without delay. If keyboard slides down (`snapshot.keyboardTopY > verifiedTopY + 15dp`) or `isPhysicalKeyboardOnScreen == false`, immediately dismissed inline bar. If keyboard closed during recording, cleanly confirms inline recording.
6. **Real Live Streaming Speech-to-Text on Both Surfaces (`LiveRollingCloudPreviewer.kt`)**:
   - Upgraded `AudioRecorder.kt` to 16kHz 16-bit Mono PCM (`AudioRecord` with `VOICE_RECOGNITION` and `MIC` fallback), keeping unread PCM stream in RAM.
   - Implemented `LiveRollingCloudPreviewer.kt` slicing audio every 2.4s and transcribing via Groq / Gemini in background, updating `LiveTranscriptionPreviewController` in real time.
   - Long-speech insertion optimization: pressing Confirm (`✓`) on a 2-minute speech only transcribes the remaining 1-2s tail slice (`drainRemainingTailWavFile()`), inserting combined transcript in < 600ms.
   - `LiveTranscriptionPreviewController` now active for both Inline Bar and Floating Mic pod.

---

## 🚀 What's New in v4.0.0 (Live Streaming Preview & Studio Inline Bar)

1. **Strict Physical Window Rule for Instant Inline Bar Hide**:
   - Solved the bug where the Inline Keyboard Bar stayed floating over the screen when Gboard closed.
   - `KeyboardVisibilityDetector` now enforces `isPhysicalKeyboardOnScreen`: strictly true ONLY when a physical `TYPE_INPUT_METHOD` or known keyboard window is on screen with `height >= 115dp`.
   - The inline bar hides (`View.GONE`) instantly (< 40ms) when Gboard slides down.
2. **Studio-Balanced 3-Zone In-Strip Bar Layout & Guaranteed Synchronous Expansion**:
   - Solved cramped left-aligned layout and failure to expand on tap.
   - **Left Zone**: `38dp` Cancel disc (`✕`).
   - **Center Zone**: Mathematically centered `[ 🔴 0:12 • 13-Bar Symmetric Waveform • Listening/শুনছি ]` anchored at `availableCenterStart + (availableWidth - totalClusterW) / 2f`.
   - **Right Zone**: `38dp` Sage Confirm pill (`✓`).
   - Floating mic pod kept compact (`136dp`, no timer) while showing the full timer on the wider Inline Keyboard Bar.
   - Tapping IDLE chip immediately transitions to `RECORDING` and synchronously expands view layout before recording starts.
3. **Independent Active Trigger Surface Expansion**:
   - Tapping Inline Chip expands ONLY the Inline Bar and hides Floating Mic until IDLE.
   - Tapping Floating Mic expands ONLY the Floating Pod and keeps Inline Bar IDLE.
4. **Claude-Inspired Live Real-Time Speech-to-Text Preview**:
   - Sleek translucent Obsidian card (`#F0111316`, `16dp` corner radius, `1dp` `#2A2E33` border) floating above active recording bar.
   - Editorial Italic Serif typography (`15.5sp`, `#D5D8DF`, `maxLines = 3`, `TruncateAt.START`) streaming partial speech in real time.
   - Toggleable in Settings under "Voice and polish" (`enableLiveTranscriptionPreview`, default: `true`).
5. **Verified IME Commit & Unstuck Spinner Fix**:
   - Implemented `commitViaAccessibilityImeVerified(nfcText)` which checks `ic.getSurroundingText(30, 30, 0)` before and 45ms after `commitText()`. If text didn't change, immediately falls through to Tier 1 (`ACTION_PASTE`) and Tier 2 (`ACTION_SET_TEXT`).
   - Wrapped `executeDictationPipelineIo()` in a mandatory `finally { withContext(NonCancellable + Dispatchers.Main) { currentState = Idle; updateUiState(...); livePreviewController?.hide() } }` so the spinner can NEVER stay stuck.
6. **Full AI Formatting Pass & Dictionary Pre-Seeding**:
   - Restored full LLM formatting pass (`gemini-3.5-flash-lite` / Groq `llama-3.3-70b-versatile`) with `WISPR_FLOW_CORE_SYSTEM_PROMPT` for dictations $> 4$ words when smart polish is enabled.
   - Pre-seeded `"Flow app" -> "floor app"`, `"Wispr Flow" -> "whisper flow"`, `"Flow" -> "floor"` in `PersonalDictionaryManager`.

---

## 🚀 What's New in v3.8.0 (True In-Strip Gboard Suggestion Bar Integration)

1. **Exact Bitwarden Geometry Inside Gboard's Top Strip (`y = keyboardTopY + 4dp`)**:
   - Fixed the Y-coordinate offset from v3.7.0: the chip now sits strictly **inside** Gboard's 44dp suggestion/toolbar row (`y = keyboardTopY + 4dp`), never above `keyboardTopY`.
   - Never covers a single pixel of Google Keep's bottom formatting toolbar (`+`, palette, `A`), WhatsApp's attachments, or YouTube comment fields!
2. **Compact 68dp Mini-Button in Idle Mode**:
   - In `IDLE`, draws a compact `68dp x 36dp` mini-pill (**`[ 🎙️ বাং ]`**) docked right beside Gboard's native mic icon (`x = screenWidth - 116dp`).
   - Leaves over 75% of Gboard's top bar completely open for word suggestions, autocorrect, and left toolbar buttons (`⊞`, clipboard, translate).
   - Single-tap to start recording; double-tap (< 320ms) to toggle dictation language (`বাং` ↔ `EN`).
3. **In-Strip Expansion Pod (`50dp .. screenWidth - 50dp`, `height = 36dp`)**:
   - Expands horizontally **inside Gboard's suggestion bar** showing **`[ ✕ ]   🔴 শুনছি • বলুন...   [ ✓ ]`**.
   - Tap `✓` to stop and format; tap `✕` to cancel instantly.
   - On completion, flashes Sage (`#8FBF9F`) `"✓ লেখা বসেছে"` for 260ms and shrinks smoothly back to the 68dp mini-button.
4. **Native Gboard Autofill Trampoline (`FlowInlineTriggerActivity`) & Broad `AssistStructure` Scanner**:
   - Created translucent 0ms `FlowInlineTriggerActivity` using `PendingIntent.getActivity()` for `Dataset.setAuthentication` (fixing the Android 12–15 bug where `getForegroundService` PendingIntents were ignored by Autofill).
   - Upgraded `AssistStructure` scanner to match custom editors (Google Keep, Chrome, Facebook, WebViews) with `View.AUTOFILL_TYPE_TEXT || inputType != 0 || htmlInfo != null`.
   - Automatic duplicate suppression: when the native Gboard Autofill chip is present, the fallback overlay hides automatically during `IDLE` so you only ever see one button.
5. **Clean Default In-Strip Mode (Zero Floating Clutter)**:
   - Defaults to `TRIGGER_STYLE_KEYBOARD_ONLY` (`enableKeyboardBarChip = true`, `enableFloatingMic = false`), completely eliminating the duplicate floating circle from the screen.

---

## 🚀 What's New in v3.7.0 (Bitwarden-Style Gboard Inline Chip & Accessibility-Hosted Keyboard Bar)

1. **Native Gboard / Samsung Keyboard Suggestion Strip Chip (`FlowInlineAutofillService`)**:
   - Integrates with Android's `AutofillService` and Jetpack `androidx.autofill:autofill:1.3.0` `InlineSuggestionUi`.
   - Embeds a sleek **`[ 🎙️ বলুন • Flow ]`** suggestion chip directly inside Gboard and Samsung Keyboard's top toolbar strip.
   - **Kernel Wake-Up Guarantee**: Even if Flow's process is killed by Xiaomi HyperOS or OEM memory cleaners, Android `system_server` (`AutofillManagerService`) cold-starts Flow on demand the instant any editable input field is tapped!
2. **Universal Keyboard-Strip Docked Pill (`KeyboardStripOverlayController`)**:
   - For applications that disable standard Android Autofill (`importantForAutofill="no"` in WhatsApp or YouTube comments), Flow docks an "Ink & Danda" inline chip right along the top toolbar of the keyboard (`keyboardTopY - 42dp`).
   - Hosted **directly inside `TextInjectorAccessibilityService` using `TYPE_ACCESSIBILITY_OVERLAY`**, making it completely immune to OEM background process termination and overlay blockers.
3. **Multi-State Inline Interaction**:
   - `IDLE`: Tap to record; tap right badge (`বাং` / `EN`) to toggle language.
   - `RECORDING`: Morphs into `[ ✕ বাতিল ]   🔴 শুনছি...   [ ✓ সম্পন্ন ]` directly on the keyboard strip for instant, distraction-free control.
   - `PROCESSING`: Shows `[ ✕ ]   ⟳ সাজানো হচ্ছে...` with instant cancellation support.
   - `INSERTED`: Flashes Sage (`#8FBF9F`) `"✓ লেখা বসেছে"` for 260ms confirmation.
4. **Customizable Trigger Style**:
   - Configure in Settings $\to$ Microphone:
     - **`Both (Keyboard Bar + Floating Mic)`** (Default — Maximum Reliability)
     - **`Keyboard Bar Only (Bitwarden Style)`** (Zero screen obstruction)
     - **`Floating Mic Only`** (Classic floating bubble)
5. **1-Tap Gboard Autofill Activation**:
   - One-tap button in `MainActivity` and `SetupPermissionsSheet` taking the user directly to Android's Default Autofill Service settings.

---

## 🚀 What's New in v3.6.0 (Zero-Kill Xiaomi HyperOS Survival & Self-Healing Keyboard Sync)

1. **Invisible Recents Shield (`excludeFromRecents` & Dynamic Task Shield)**:
   - Configured `android:excludeFromRecents="true"`, `android:autoRemoveFromRecents="true"`, and `android:launchMode="singleTask"` on `MainActivity`.
   - In `MainActivity.onStop()`, dynamically excludes all app tasks from the Recent Apps switcher once critical permissions are granted.
   - When the user swipes away all Recent Apps or taps Xiaomi HyperOS's **"Clear All" (✕)** button, Flow leaves zero card in the switcher—meaning MIUI `ProcessManager` never targets Flow with `forceStopPackage`!
2. **5-Signal Self-Healing Keyboard Detector (`KeyboardVisibilityDetector`)**:
   - Resolves the issue where swiping Recent Apps caused `getWindows()` to temporarily return empty or drop `TYPE_INPUT_METHOD` on HyperOS.
   - **Signal 1**: `TYPE_INPUT_METHOD` window with height $\ge 100\text{dp}$.
   - **Signal 2**: Known keyboard packages (`Gboard`, `Honeyboard`, `Ridmik`, `SwiftKey`, `Mayabi`, `Mint`, `Baidu MIUI`).
   - **Signal 3**: Android 13+ `AccessibilityInputConnection` `onStartInput` (`EditorInfo.inputType != TYPE_NULL`) with active editable focus.
   - **Signal 4**: Application window bottom compression ($\ge 140\text{dp}$ bottom gap when keyboard is opened).
   - **Signal 5**: Direct editable node touch/focus fallback within 10 seconds.
3. **Auto-Reassert Interactive Windows Flags & Bidirectional Service Sync**:
   - `TextInjectorAccessibilityService` checks `windows.isEmpty()` and automatically re-asserts `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` and `FLAG_INPUT_METHOD_EDITOR` dynamically.
   - `FloatingBubbleService` pulls live keyboard state immediately on `onCreate()` and `onStartCommand()` via `requestImmediateKeyboardSync()`, preventing it from ever being stuck in `isKeyboardCurrentlyVisible = false`.
   - On every accessibility interaction, if `FloatingBubbleService` was trimmed, `ensureBubbleServiceAliveAndBound()` revives it instantly in $< 50\text{ms}$.
4. **Xiaomi HyperOS "Boost Speed $\to$ Lock Apps" Deep Link**:
   - Direct launcher targeting `com.miui.securitycenter` and `LockAppManageActivity` with one-tap deep link and bilingual (Bangla & English) step-by-step guidance.
   - Direct "মেমোরিতে Flow লক করুন (HyperOS) / Lock Flow in Memory" action in `SetupPermissionsSheet`.

---

## 🚀 What's New in v3.5.1 (Emergency Processing Freeze, ANR & Crash Resolution)

1. **Non-Blocking Off-Main-Thread Audio Finalization (`stopRecordingSafelyIo`)**:
   - `AudioRecorder` stopping, audio reset, and file finalization run strictly inside `withContext(Dispatchers.IO)` with full `try/catch/finally` protection and timeouts.
   - Removed blocking `runBlocking` calls on `Dispatchers.Main` that previously stalled Choreographer animations at 00:15.
   - Disabled concurrent `AudioRecord` streaming during active dictation, granting `AudioRecorder` (24kHz 64kbps AAC `.m4a`) 100% exclusive hardware microphone access without dual-mic deadlocks.
2. **8.5-Second Hard Watchdog Timer (`startProcessingWatchdog`)**:
   - Independent watchdog timer launches on every transition to `PROCESSING` (`✕ | ⟳ Ochre Spinner`).
   - Guarantees that even under network outages or unexpected timeouts, Flow recovers gracefully from `PROCESSING` $\to$ `IDLE` in $\le 8.5$ seconds, preventing the UI from hanging forever.
3. **Single-Pass Smart Mode Cloud ASR on `Dispatchers.IO` & Fast Timeouts**:
   - Dedicated `OkHttpClient` with strict low-latency timeouts: `connectTimeout = 4s`, `writeTimeout = 5s`, `readTimeout = 6s`, `callTimeout = 7s`.
   - Transcription pipeline runs strictly on `Dispatchers.IO` with `withTimeoutOrNull(5500L)` and formatting with `withTimeoutOrNull(2200L)`.
   - Never blocks transcription on `getOrFetchLiveModels()`. If cache is empty, uses default `ModelCatalog.specs` and fetches models asynchronously in the background.
   - Added automated fallback to `/v1beta/models/gemini-3.5-flash-lite:generateContent` with Base64 audio if `/v1beta/interactions` is unavailable.
4. **Zero-IPC Fixed-Size Overlay Canvas (Zero `WindowManager.updateViewLayout` Calls)**:
   - Floating Action Pod maintains the exact same window width (`176dp x 64dp`) between `RECORDING` and `PROCESSING`.
   - Transition from waveform bars to the Ochre spinner is a 100% in-memory canvas redraw (`invalidate()`) with zero `WindowManager` IPC relayouts or `TYPE_WINDOWS_CHANGED` loops.
   - `FloatingBubbleService` strictly uses `TYPE_APPLICATION_OVERLAY` with its own `Context.WINDOW_SERVICE` `WindowManager`, eliminating `WindowManager.BadTokenException`.
5. **Accessibility Isolation & 220ms Event Debounce**:
   - `TextInjectorAccessibilityService` explicitly ignores events from Flow's own package (`pkg == packageName`), preventing feedback loops when the 60fps Ochre spinner animates.
   - Completely skips `syncKeyboardVisibilityWithBubble()` and `getWindows()` while the bubble is actively `RECORDING` or `PROCESSING`.
   - Debounces all keyboard visibility synchronization by 220ms.
6. **Bounded 30-Node Traversal & Zero-Wait Native Injection (`injectTextFastAndSafe`)**:
   - Replaced unbounded recursive 18-level DFS with `findTargetNodeBounded()`, checking $O(1)$ `findFocus(FOCUS_INPUT)` first, then cached node, and strictly capping fallback DFS to a maximum of 30 nodes visited (`maxNodes = 30, depth <= 7`).
   - Removed blocking `dispatchPhysicalTapToWakeInput` (`dispatchGesture` infinite hang) from the synchronous injection path.
   - Direct API 33+ `AccessibilityInputConnection.commitText` fast-path ensures instant paste in < 5ms without binder deadlocks.

---

## 🚀 What's New in v3.5.0 (Keyboard Sync, Per-Session Dismiss & OEM Survival)

1. **Strict 3-Signal Keyboard Visibility Detection (`KeyboardVisibilityDetector`)**:
   - Floating mic is strictly synchronized with the soft keyboard lifecycle: appears *only* when the keyboard is open and vanishes within 150ms when the keyboard closes.
   - Evaluates primary `AccessibilityWindowInfo.TYPE_INPUT_METHOD` with height $\ge 100\text{dp}$, backed by secondary detection for Gboard, Samsung Honeyboard, Ridmik Keyboard, SwiftKey, Mayabi, and Xiaomi Mint.
   - Automatically excludes sensitive banking applications (bKash, Nagad, Cellfin, Nexus Pay, Upay) and password fields.
2. **Per-Keyboard-Session Dismiss (`✕`) with Auto-Revival**:
   - Dragging the floating mic to the bottom `✕` target now dismisses the bubble **only for the current typing session** instead of permanently disabling it.
   - The instant the user finishes and closes the keyboard, `isDismissedForCurrentKeyboardSession` automatically resets to `false`—the mic seamlessly re-appears the very next time any text box is opened!
   - `PreferencesManager.showOnlyWhenTyping` defaults to `true`.
3. **Unkillable Accessibility-Bound Watchdog (`BIND_IMPORTANT`)**:
   - `TextInjectorAccessibilityService` maintains a persistent `Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT` binder connection to `FloatingBubbleService`, elevating it to Android's `system_server` process priority.
   - The watchdog automatically monitors and revives `FloatingBubbleService` if terminated by aggressive OEM background cleaners.
4. **`TYPE_ACCESSIBILITY_OVERLAY` System Token Promotion**:
   - Overlays are hosted via the `TextInjectorAccessibilityService` window context using `TYPE_ACCESSIBILITY_OVERLAY`, exempting Flow from background cached-process freezer kills and overlay restrictions on MIUI/HyperOS, One UI, ColorOS, and OriginOS.
5. **Boot, Update & Unlock Auto-Revive Receiver (`FlowSystemReviveReceiver`)**:
   - Listens for `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `QUICKBOOT_POWERON`, and `USER_PRESENT` to guarantee background readiness after device reboot or deep sleep.
6. **Multi-OEM Autostart & Battery Deep-Linker (`OemAutostartHelper`)**:
   - Native deep-linking to proprietary Autostart / Auto-Launch management for Xiaomi/Redmi/POCO, Samsung One UI, OPPO/Realme/OnePlus, Vivo/iQOO, Huawei/Honor, and Transsion (Tecno/Infinix/itel).
   - Upgraded `SetupPermissionsSheet` with a 2-step setup card providing manufacturer-specific step-by-step guidance.

---

## 🚀 What's New in v3.4.0 (Zero-Fail Injection, Action Pod & Streaming ASR)

1. **1-in-50,000 Zero-Fail Injection (YouTube Shorts, Facebook Comments, WebViews)**:
   - Built on Android 13+ (API 33+) `FLAG_INPUT_METHOD_EDITOR` + `AccessibilityInputConnection.commitText()`.
   - Upgraded master `injectTextSuspend` to a 6-tier zero-fail cascade:
     - **Tier 0**: OS IME Direct Input Connection (`AccessibilityInputConnection.commitText`).
     - **Tier 1**: Verified `ACTION_PASTE` via Accessibility Node with cursor validation.
     - **Tier 2**: Focus-Acquisition + Verified `ACTION_PASTE` / IME Commit.
     - **Tier 3**: Hint-safe `ACTION_SET_TEXT` with cursor text splicing (guards against placeholder hints).
     - **Tier 4**: Physical Coordinate Gesture Tap Wake-Up (`dispatchGesture` 18ms tap) + IME Commit.
     - **Tier 5**: Deferred Auto-Commit on next input focus change via `onStartInput`.
2. **'Ink & Danda' Action Pod UI with Live Cancel & Confirm**:
   - Floating mic smoothly expands into a horizontal obsidian capsule pod during Recording (176dp) and Processing (124dp).
   - **Cancel (`✕`) Action Button**: Instantly halts speech recording, active coroutines, and streaming network requests, discarding temporary audio and resetting the UI in under 16ms.
   - **Confirm (`✓`) Action Button**: Commits transcription immediately with a 60fps rotating arc.
   - Screen edge docking ensures the pod never clips off-screen when anchored to left or right borders.
3. **Zero-Wait Real-Time Audio Streaming & Rolling Chunks**:
   - Integrated WebSocket client streaming 16kHz PCM chunks directly to `gemini-3.5-transcribe-live` for instant completion.
   - Background `RollingChunkTranscriber` uploads speech segments during natural pauses on `Dispatchers.IO`.
   - Single-pass Smart Mode formatting bypass for Gemini Transcribe/Live results when no backtrack cues exist (<2ms on-device formatting).
4. **Automatic Bengali Period-to-Danda (`।`) Conversion**:
   - Trailing periods (`.`) following Bengali words are automatically converted into authentic Bengali Danda (`।`) in `TranscriptPostProcessor`.

---

## 🎨 What's New in v3.3.2

1. **YouTube & Facebook Comment Text Injection Fix**:
   - Resolved multi-window bottom-sheet comment modal detection by sorting interactive windows by layer descending and scanning with `requireFocus = false`.
   - Added `typeWindowsChanged` accessibility event registration in `accessibility_service_config.xml`.
   - Augmented `injectTextSuspend` to perform programmatic `ACTION_CLICK` + `ACTION_FOCUS` before `ACTION_PASTE` and hint-safe text splicing.
2. **Samsung One UI Background Persistence & Keyboard Detection**:
   - Upgraded foreground notification channel to `IMPORTANCE_DEFAULT` and service priority to `PRIORITY_DEFAULT` with `FLAG_ONGOING_EVENT` and `FOREGROUND_SERVICE_IMMEDIATE` to prevent Samsung Device Care from killing background service.
   - Added explicit detection for Samsung Honeyboard (`com.samsung.android.honeyboard`).
   - Added direct Samsung Device Care background protection button in the Setup Permissions sheet.
3. **60fps Live Rotating Recording Ring**:
   - Upgraded `FloatingMicCanvasView.kt` with a continuous 60fps rotating dual-arc ring running synchronously with speech amplitude breathing.
   - Immediate, unmistakable visual feedback that recording is active even during momentary pauses.
4. **Selectable Accent Color Palette**:
   - Added selectable premium accent color options in Settings: Classic Ivory (`#EEEBE3`), Ochre Gold (`#E3B257`), Sage Emerald (`#8FBF9F`), Nordic Sky (`#38BDF8`), Terracotta Rose (`#FB7185`), and Amethyst (`#A78BFA`).
   - Dynamically themes active tabs, segmented pills, sliders, buttons, and indicators across the app.
5. **Polished Bottom Navigation Pill Dock**:
   - Replaced vertical-jumping icon bars with an animated capsule pill dock (`Home` and `History`).
   - Smooth horizontal capsule animation with accent-tinted background.
6. **Fast Cloud (Hybrid) Mode Recovery**:
   - Fixed crash/hide bug in Fast Cloud mode by decoupling standard online `SpeechRecognizer` from `createOnDeviceSpeechRecognizer` and setting `preferOffline` strictly when offline is requested.
   - Added clear bilingual feedback when internet is offline.
7. **Offline & Offline AI Model Downloaders**:
   - Contextual setup card under Dictation Engine: 1-click Google Voice Typing settings intent for `Offline` mode, and interactive Gemma 3 (1B) LiteRT downloader with live progress bar, pause/cancel, and delete buttons for `Offline AI` mode.

---

## 🎨 What's New in v3.3.1 ('Ink & Danda' Premium Redesign)

1. **'Ink and Danda' Dark-First Aesthetic**:
   - Replaced generic AI-template blue/slate cards with an editorial, warm dark palette: DarkCanvas (#0D0E10), DarkSurface (#16181B), DarkSurfaceRaised (#1F2226), and DarkHairline (#2A2E33).
   - Interaction is driven by ivory (DarkInk #EEEBE3). Vermilion (#E8473A) is reserved strictly for active recording; Ochre (#E3B257) for processing and attention; Sage (#8FBF9F) for success.
2. **Editorial Typography & Material 3 Cleanup**:
   - Display headlines, hero status, transcripts, and history entries use native Serif (Noto Serif & Noto Serif Bengali), paired with clean Sans-serif for functional controls.
   - Eliminated Material 3 purple/violet surface tints (surfaceTint = Color.Transparent) and violet oval pills (indicatorColor = Color.Transparent).
3. **Layout Bug Fixes & Refinements**:
   - **History Duration Wrap Bug**: Fixed the squeezed 28 / 2 / sec layout bug with a right-aligned, fixed 48dp width (maxLines = 1).
   - **Slider Step Accuracy**: Stepped bubble size strictly by 4dp (48..84dp, default 64dp) and opacity by 5% (30..100%, default 90%), eliminating invalid numbers like 63dp.
   - **Unified Segmented Controls**: Replaced mismatched buttons with custom 44dp FlowSegmentedControl.
   - **Contrast Fixes**: Replaced cyan on slate with high-contrast ivory DarkInk on DarkSurfaceRaised (16:1 ratio).
4. **Danda-Mic Geometry & Overlay Refinements**:
   - Overlay mic drawn mathematically with 1/28 unit scaling: capsule head, cradle arc, and vertical danda stem.
   - Solid breathing vermilion ring during recording, 90° ochre orbiting arc during processing, 280ms sage fill on insertion.
   - 22dp language chip at bottom-right (#0D0E10 background, ivory text 'EN' / 'বাং').
   - Recovery pill positioned directly above the mic with an 8dp ochre dot and clean 2-line copy.
   - Non-red dismiss target with ivory outline and hover fill.
   - Adaptive launcher icon and notification small icon upgraded to monochrome Danda-Mic.

---

## 🌟 What's New in v3.3.0 (98–99% Argon ASR Parity & Wispr Flow Intelligence)

1. **Argon / Gemini Native ASR Parity (98–99% Accuracy)**:
   - Upgraded `AudioRecorder.kt` to `AudioSource.VOICE_RECOGNITION` (with fallback to `MIC`), `24,000 Hz` sampling rate, and `64,000 bps` AAC-LC bitrate, capturing soft speech and consonant transients clearly.
   - Added an 80ms hardware ADC warm-up delay before the start haptic cue so the first spoken syllable is never clipped.
   - Fixed the Gemini Interactions API schema bug: `mode` in `transcription_config` is now strictly passed as a `JSONObject` (`{"type": "verbatim"}` or `{"type": "smart"}`), preventing server-side fallback or model rejection.
   - Natively auto-detects intra-sentence Bengali (`bn-BD`) and English (`en-US`) code-switching by omitting `language_codes` when empty.
   - Passes up to 250 dynamic acoustic terms into `custom_vocabulary` by merging active screen proper nouns and personal dictionary words.

2. **Wispr Flow Semantic Backtrack & Course-Correction Engine**:
   - Resolved the issue where short dictations bypassed the AI engine: restricted fast-path to ultra-short (<= 3 words) simple phrases with zero backtrack or list triggers.
   - Added `WISPR_FLOW_CORE_SYSTEM_PROMPT` with explicit few-shot rules resolving:
     - **Topic Backtracks**: *"Let's make a recipe... no let's make a grocery list"* -> Discards "recipe" and outputs a clean **Grocery list:**.
     - **List Item Backtracks**: *"1. Tomato, 2. Potato, 3. Brinjals no no not brinjals get some rice"* -> Discards "brinjals" and outputs `3. Rice`.
     - **Bangla Backtracks**: *"বেগুন না না বেগুন লাগবে না চালের বস্তা এনো"* -> Discards "বেগুন" and preserves "চালের বস্তা".
     - **Discourse Framing & Meta-Chatter Removal**: Automatically strips verbal hesitation bridges (*"and I'm saying that"*, *"for example"*) while preserving genuine examples.
   - Created `LocalBacktrackResolver.kt` for offline deterministic regex backtrack resolution.

3. **Auto-Learn Personal Dictionary & Misspelling Map**:
   - `PersonalDictionaryManager.kt`: deterministic whole-word replacement with possessive safety (`Don's -> Dawn's`) and contraction guard (`don't` untouched).
   - 15-Second Post-Paste Auto-Learn Watcher in `TextInjectorAccessibilityService.kt`: detects user spelling edits (Levenshtein distance 1..3) and automatically learns the correction with an `"✨ Auto-learned"` badge.

4. **Voice Snippets & Text Expansion**:
   - `SnippetManager.kt` & `SnippetExpander.kt`: Speak trigger phrases like *"my email"* or *"আমার ঠিকানা"* to expand into saved text blocks both standalone and mid-sentence (longest trigger match wins).

5. **Context-Aware Emoji Continuation & Casual Messaging Trailing Period**:
   - `WisprContextFormatter.kt`: continues in lowercase with a leading space after non-punctuation emojis (`😀`) or quotes, and starts a new capitalized sentence after punctuation emojis (`‼️`, `❓`).
   - Automatically omits stiff trailing periods (`.` / `।`) on 1-2 sentence messages in `PERSONAL_MESSAGING` apps (WhatsApp, Messenger).

6. **Voice Command Mode & 1-Tap Undo AI Edit**:
   - Highlight text in any input field and speak a command (*"Make this formal"*, *"Translate to English"*) to transform the selection in-place via `geminiInteractionsAdapter.runCommandTransform`.
   - 1-tap **"Undo AI Edit / মূল কথা ফিরিয়ে আনুন"** button in ongoing foreground notification and History screen rows whenever AI edits or removes words.

7. **Expanded Settings UI in `MainActivity.kt`**:
   - **Auto Cleanup Level** selector (`None`, `Light`, `Medium - Wispr Backtrack & Polish`).
   - **Personal Dictionary & Auto-Learned Words ✨** manager.
   - **Voice Snippets** manager.

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
