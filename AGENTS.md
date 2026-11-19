# AGENTS.md — Operational Guardrails for Autonomous Engineering Agents

> **Audience**: AI Agents (Antigravity, Claude, Codex, Gemini, etc.) and Human Contributors.  
> **Repository**: Flow (Wispr Flow Replacement for Android)

---

## 1. Core Invariants (Absolute Rules)

1. **NEVER Hard-Code Model IDs Outside `models_registry.json`**:
   - Model deprecations are frequent across AI providers.
   - All active model IDs, fallback IDs, and retired IDs must be declared in `app/src/main/assets/models_registry.json` and accessed via `ModelRegistry.kt`.
   - The JVM unit test `ModelRegistryTest` will fail the build if any retired model appears in code.

2. **NEVER Route Bangla or Mixed Speech to Groq Whisper**:
   - Whisper (`whisper-large-v3` / `turbo`) has a ~30.7% character error rate on Bengali and completely mangles Banglish code-switching.
   - Groq Whisper is hard-gated to `SpeechLang.ENGLISH`.
   - For `SpeechLang.BANGLA` and `SpeechLang.MIXED`, always route to Sarvam AI (`saaras:v3` with `mode = "codemix"`), Soniox, Gemini 3.5 Transcribe, ElevenLabs, Deepgram, or On-Device STT.

3. **NEVER Add Cloud HTTP Polling During Recording**:
   - Background audio slice polling burns through free-tier rate limits (e.g. 20 RPM on Groq) and wastes cellular battery.
   - 1 dictation = 1 cloud ASR request upon user confirmation (`✓`).
   - Live preview text is reserved exclusively for WebSocket streaming providers or Android On-Device SpeechRecognizer.

4. **Invariant I1 (Voice Vault First)**:
   - Always persist audio to `filesDir/voice_vault/` **before** any network call is initiated. If network drops or timeouts occur, user audio is preserved.

5. **Invariant I2 & I3 (Raw Transcript Safe Fallback)**:
   - Always persist the raw transcript in `HistoryManager` before triggering LLM formatting.
   - If LLM polish fails or times out, always fall back to injecting and copying the raw transcript. Spoken user words must never be lost.

---

## 2. Line Ending and Platform Hygiene

- **CRLF Preservation**: This is a Windows-hosted project (`\r\n`). All Kotlin, XML, JSON, and Gradle files must retain Windows `\r\n` line endings.
- **Python Execution**: When writing file transformation scripts, write them as temporary `.py` files in `scratch/` and execute with `python path/to/script.py`. Do not pass complex multi-line inline scripts in PowerShell command lines.

---

## 3. Mandatory Pre-Commit & Release Verification

Before committing any change or building release artifacts:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

Every successful release build must be copied to:
1. Workspace root: `Flow-v<VERSION>.apk`
2. User root folder: `D:\Projects\Bevel for Android\Flow-v<VERSION>.apk`
3. Mirror workspace: `d:\Projects\Whisper Flow Replacemet`
