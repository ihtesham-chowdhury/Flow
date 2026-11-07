# Flow — Complete UI, Visual Design & Interaction Specification

> **Target Version**: Flow v3.3.1 (Build 11)  
> **Design Philosophy**: "Ink & Danda" — Warm Dark Obsidian, Editorial Typography (Noto Serif Display & Transcripts), Purposeful Interaction (Ivory Accent, Vermilion Live Recording, Ochre Processing, Sage Success)  
> **Primary Audience**: UI/UX Designers, Android Engineers, and LLM Agents (Claude, ChatGPT, Gemini) seeking an exact, pixel-accurate mental model of Flow's interface, typography, visual hierarchy, animations, and runtime behaviors.

---

## 1. Design System & Design Tokens

Flow rejects generic AI-template aesthetics (electric blues, purple glows, neon cyan borders, and emoji sparkles) in favor of **Ink and Danda**: an editorial, distraction-free environment that celebrates Bengali and mixed-script literature.

### 1.1 Color Palette & Semantic Roles

| Token Name | Hex Code | Compose Color | Semantic Usage & Meaning |
| :--- | :--- | :--- | :--- |
| **`DarkCanvas`** | `#0D0E10` | `Color(0xFF0D0E10)` | Master background, system status bar, navigation bar, flat adaptive launcher background. |
| **`DarkSurface`** | `#16181B` | `Color(0xFF16181B)` | Group card containers (20dp radius), bottom sheet surfaces, dialog backdrops. |
| **`DarkSurfaceRaised`** | `#1F2226` | `Color(0xFF1F2226)` | Selected engine mode row background, recovery pill fill, pressed states. |
| **`DarkSurfaceSunken`** | `#111316` | `Color(0xFF111316)` | Text fields, segmented control track background (inset, sunken). |
| **`DarkHairline`** | `#2A2E33` | `Color(0xFF2A2E33)` | 1dp crisp structural divider between rows and list entries. |
| **`DarkInk`** | `#EEEBE3` | `Color(0xFFEEEBE3)` | Ivory. Primary text, primary interactive buttons, active radio dots, slider thumbs. |
| **`DarkInkMuted`** | `#9EA3AB` | `Color(0xFF9EA3AB)` | Secondary captions, timestamps, inactive icons, helper text, unselected options. |
| **`DarkInkOnAccent`** | `#0D0E10` | `Color(0xFF0D0E10)` | Obsidian foreground text/glyphs when drawn on ivory buttons or idle mic disc. |
| **`VermilionDark`** | `#E8473A` | `Color(0xFFE8473A)` | **Live recording only**. Reserved exclusively for active voice capture. |
| **`OchreDark`** | `#E3B257` | `Color(0xFFE3B257)` | Processing, attention, permissions warning dot, recovery pill status dot. |
| **`SageDark`** | `#8FBF9F` | `Color(0xFF8FBF9F)` | Success, permission checkmarks, 280ms insertion feedback disc. |

---

### 1.2 Typography & Text Hierarchy

Typography utilizes Android's native serif (Noto Serif & Noto Serif Bengali) for display titles, hero status, transcripts, and history entries, balanced with clean sans-serif for functional controls.

| Role | Font Family | Size / Line Height | Color | Usage Example |
| :--- | :--- | :--- | :--- | :--- |
| **Display Headline** | `FontFamily.Serif` | 30sp / 38sp (Bn: 44sp) | `DarkInk` | Hero status (`Ready.`, `Paused.`, `Needs setup.`), History screen title |
| **Screen Title** | `FontFamily.Serif` | 22sp / 28sp | `DarkInk` | TopAppBar App Title (`Flow`) |
| **Section Label** | `FontFamily.SansSerif` | 13sp / 18sp (Medium) | `DarkInkMuted` | Group titles (`Dictation`, `Keys`, `Voice and polish`) |
| **Row Label** | `FontFamily.SansSerif` | 15sp / 22sp (Medium) | `DarkInk` | Switch rows, segmented options, radio titles |
| **Caption** | `FontFamily.SansSerif` | 12sp / 16sp | `DarkInkMuted` | Supporting descriptions, timestamps |
| **Fixed Duration** | `FontFamily.SansSerif` | 12sp (Fixed 48dp, `maxLines=1`) | `DarkInkMuted` | Right-aligned duration in History (fixes `28 / 2 / sec` wrap bug) |
| **Transcript** | `FontFamily.Serif` | 17sp / 28sp (Bn: 30sp) | `DarkInk` | History transcript body, live preview caption |

---

### 1.3 Material 3 Surface Tint & Violet Pill Elimination

In standard Material 3, navigation bars, bottom sheets, and elevated cards inject a noticeable purple/violet cast (`surfaceTint`). Flow completely eliminates this:
- `surfaceTint = Color.Transparent` in `Theme.kt`.
- `indicatorColor = Color.Transparent` on bottom navigation bar items (replacing violet oval pill with a clean 24dp x 2dp ivory bar above the active icon).
- Sliders and switches use custom ivory tracks and thumbs, never default violet-grey tints.

---

## 2. Floating Overlay & Danda-Mic Geometry

The overlay floats above keyboard and third-party apps, drawn mathematically via `FloatingMicCanvasView` with unit coordinates where **1 unit = 1/28 of disc radius**:

```
        ┌────────────────────────────────┐
        │                                │
        │     ((((  ╭────────╮  ))))     │  <-- Breathing Vermilion Ring (r = disc + 4 + amp*4)
        │           │  ╭──╮  │           │  
        │           │  │  │  │           │  <-- Capsule (Mic Head, 6u x 14u, corner 3u)
        │           │  ╰──╯  │           │
        │           │  ╰──╯  │  ╭────╮   │  <-- Cradle (Half-circle arc, r=7u) & Stem (Danda)
        │           │   │    │  │ বাং │   │  <-- Language Chip (22dp circle at bottom-right)
        │           ╰───┴────╯  ╰────╯   │
        │                                │
        └────────────────────────────────┘
```

### 2.1 State Progression Across Overlay

| State | Disc Fill | Center Glyph | Voice Ring / Animation | Caption / Overlay |
| :--- | :--- | :--- | :--- | :--- |
| **Idle** | Ivory `#EEEBE3` | Obsidian `#0D0E10` | 1dp 20% alpha keyline | None |
| **Recording** | Vermilion `#E8473A` | Ivory `#EEEBE3` | Solid breathing vermilion ring (70% alpha) | Live caption above mic |
| **Processing** | Ivory at 80% | Obsidian at 60% | 90° Ochre `#E3B257` orbiting arc (1100ms/rev) | Transient status |
| **Inserted** | Sage `#8FBF9F` (280ms) | Obsidian `#0D0E10` | Fills sage, returns to idle over 200ms | None |
| **Error** | Ivory `#EEEBE3` | Obsidian `#0D0E10` | Dashed muted ring (`#9EA3AB`) | Recovery pill above mic |

### 2.2 Live Caption (§7.2)
- Anchored above floating mic.
- Background: `#16181B` at 94% alpha, 1dp `#2A2E33` hairline, 16dp corner radius, 280dp maximum width.
- Text: Ivory `#EEEBE3`, Serif 15sp, maximum 2 lines.

### 2.3 Recovery Pill (§7.3)
- Positioned directly above the mic (not pinned to top of screen).
- Fully rounded 24dp pill, `#1F2226` fill, 1dp `#2A2E33` hairline.
- Left: 8dp Ochre dot (`#E3B257`), no emojis.
- Line 1: `Copied` / `কপি হয়েছে` (Ivory 13sp medium).
- Line 2: `Tap a text box to paste` / `লেখার ঘরে ট্যাপ করুন` (Muted 11sp).
- Tapping immediately retries paste; auto-dismisses after 45 seconds.

### 2.4 Dismiss Target (§7.4)
- Appears only while dragging mic.
- 64dp circle, bottom center, 48dp above navigation bar inset.
- Resting: 1dp ivory outline at 40% alpha, transparent fill, text `✕` in muted ivory.
- In proximity (hover): Fills ivory `#EEEBE3`, close glyph drawn in dark `#0D0E10`.
- **Never red** (red is strictly reserved for live recording).

---

## 3. Screen Specifications

### 3.1 Home Screen
1. **Hero Block**:
   - Status sentence: `Ready.` (mic on), `Paused.` (mic off), `Needs setup.` (permissions missing).
   - Caption: `The mic shows above your keyboard.` or `Turn on access to start.`
   - Live 56dp Danda-Mic disc on right: mirrors overlay state; tapping toggles the mic.
2. **Show mic row**:
   - Switch in ivory, starts/stops floating overlay with permissions check.
3. **Setup row**:
   - Displays `3 of 3 done` (Sage check) or `X of 3 needs attention` (Ochre dot).
   - Tapping opens `SetupPermissionsSheet`.
4. **Dictation (Engine Mode)**:
   - Clean radio rows:
     - `Cloud audio` — *Best for mixed Bangla and English. Needs internet and a key.* [Recommended]
     - `Fast cloud` — *Quick results. Transcribes on the phone, then polishes in the cloud.*
     - `Offline` — *No internet. Basic punctuation and filler removal.*
     - `Offline AI` — *No internet. Larger download. Uses more memory while active.*
5. **Keys Group**:
   - Four rows: Gemini, Groq, Cerebras, OpenRouter showing `Added` (with Sage check) or `Not added` (`DarkInkMuted`).
   - Tapping opens `KeyBottomSheet`.
   - Full-width `Test connection` button running 3-stage connectivity test.
6. **Voice and Polish Group**:
   - `Smart polish` switch.
   - `Clean-up` segmented control: `Off` | `Light` | `Full`.
   - `Drop the final full stop in chats` switch.
7. **Microphone Group**:
   - 96dp Live Preview box showing current mic size and opacity.
   - `How to start` segmented control: `Tap to start` | `Hold to talk`.
   - `Size` slider: 48 to 84dp, stepped by 4dp (stops: 48, 52, 56, 60, 64, 68, 72, 76, 80, 84).
   - `Opacity` slider: 30 to 100%, stepped by 5% (stops: 30, 35, 40 ... 90, 95, 100).
   - `Haptic feedback` switch.
   - `Show only when typing` switch.
   - `Hide mic` / `Show mic` text button.
8. **Vocabulary Group**:
   - `Personal dictionary` row showing entry count.
   - `Voice snippets` row showing snippet count.
9. **About Group**:
   - `Appearance` segmented control: `System` | `Dark` | `Light`.
   - `Guide` row (opens Setup Guide).
   - `Diagnostics` row (opens 3-stage report).
   - Version info: `Version 3.3.1 (11)`.

### 3.2 History Screen
- Header: Display serif 30sp `History`, caption count `${size} dictations`, text button `Clear all` in `DarkInkMuted` at right.
- List items:
  - Separated by `DarkHairline` (no cards, no borders, no box fills).
  - Meta row: timestamp on left, duration on right (`maxLines = 1`, fixed 48dp width, right-aligned). Never wraps!
  - Transcript: Serif 17sp / 28sp (Bn: 30sp), max 4 lines with `More` button for long entries.
  - Provenance: Caption `DarkInkMuted`.
  - Actions:
    - `Undo AI edit` text button (ochre glyph) shown only when raw transcript differs.
    - `Copy` text button with 1.2s `Copied` confirmation.
- Empty state: Display serif `Your dictations appear here.` in `DarkInkMuted` (no microphone illustration).

---

## 4. Verification & Quality Checklist

- [x] Zero purple/violet surface tints across all Material 3 components.
- [x] No emojis (✨, 📋, 🗣) in primary UI copy or badges.
- [x] No cyan on slate contrast failures.
- [x] Sliders snap strictly to 4dp and 5% grids (no 63dp or fractional values).
- [x] History duration never wraps vertically (`28 / 2 / sec` eliminated).
- [x] 100% functional preservation of voice recording, text injection, personal dictionary, and snippets.
- [x] All 49 unit tests pass.
