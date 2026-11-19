# Flow (Wispr Flow Replacement for Android) — Project Context

> **Version**: 5.0.0 (Build 26)  
> **Status**: Production Release Active  
> **Documentation Re-Architecture**: In Flow v5.0.0, the legacy 80KB documentation was restructured into three focused documents to keep AI assistant context windows fast, lean, and strictly up-to-date.

---

### Master Documentation Index

1. **[ARCHITECTURE.md](file:///d:/Projects/Whisper%20Flow%20Replaceme/ARCHITECTURE.md)**  
   - Complete architectural overview of all modules (`api`, `data`, `format`, `service`, `ui`).
   - Golden Invariants I1–I5 (Voice Vault audio preservation, raw transcript persistence, fallback injection, zero background HTTP slice polling, sub-300ms circuit breaker).
   - Language-gated ASR waterfall specifications (Sarvam Saaras, Soniox STT, Gemini 3.5, ElevenLabs Scribe, Deepgram Nova-3).
   - Security hardening (Keystore-backed `EncryptedSharedPreferences`, Privacy Mode, `allowBackup="false"`).

2. **[CHANGELOG.md](file:///d:/Projects/Whisper%20Flow%20Replaceme/CHANGELOG.md)**  
   - Chronological release history and changelog from v1.0.0 prototype to v5.0.0 master release.

3. **[AGENTS.md](file:///d:/Projects/Whisper%20Flow%20Replaceme/AGENTS.md)**  
   - Strict operational guardrails for autonomous AI coding agents working on this repository.
   - Retired model protection rules and CRLF line ending hygiene.
