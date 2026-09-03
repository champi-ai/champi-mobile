# champi-android — Specs & Requirements

**Status:** working draft · **Owner:** Oscar · **Date:** 2026-09-01

Decisions locked so far:

| Decision | Choice |
|---|---|
| Bubble placement | System overlay over all apps |
| Compute placement | Edge-first. Everything that can run on-device does. Heavy work goes to a remote provider. |
| Integration model | Providers. No transport, protocol, or connection design in this spec. |
| Stack | Kotlin + Jetpack Compose, native Android |

---

## 1. Overview

champi-android is champi on a phone. A small animated mushroom floats over every app; you tap it or talk to it and champi answers. The app is built as a set of capabilities (wake word, speech-to-text, language model, text-to-speech, actions) each fulfilled by a **provider**. The app prefers on-device providers and only reaches for remote ones when the task is too heavy for the phone. How remote providers are reached is out of scope here.

### Goals
- Always available without switching apps.
- Voice-first, text as an equal fallback.
- Feels alive: the character reacts to what is happening.
- Works with no network for everything the phone can do alone.
- Provider boundaries clean enough that any stage can be swapped without touching the UI.

### Non-goals (v1)
- iOS.
- AccessibilityService screen reading.
- Any definition of how remote providers communicate.
- Multi-device coordination.

---

## 2. Product Requirements

### 2.1 The bubble (overlay)
- **B1** Draws over other apps via `SYSTEM_ALERT_WINDOW` / `TYPE_APPLICATION_OVERLAY`.
- **B2** Draggable; snaps to nearest screen edge on release; position persisted.
- **B3** Drag to bottom-center dismiss zone hides the bubble until relaunch or wake word.
- **B4** Single tap → expands to conversation panel. Tap outside or swipe down → collapses.
- **B5** Long-press → quick-actions surface (mute mic, push-to-talk, sleep, settings). Interaction geometry not yet decided — see §7.
- **B6** Never covers the IME; auto-hides in immersive/full-screen apps; honours a user DND schedule.
- **B7** ~56 dp collapsed; hit target ≥ 48 dp.
- **B8** Peek state: half-tucked under the edge after N idle minutes.

### 2.2 The character
- **C1** Rendered with **Rive** (`rive-android`), driven by a state machine.
- **C2** State machine inputs:
  - `state` (enum): `idle`, `listening`, `thinking`, `speaking`, `notifying`, `error`, `sleeping`
  - `level` (0–1): mic amplitude while listening, playback amplitude while speaking
  - `attention` (0–1): eye/cap tilt toward the finger during drag or during quick-action selection
  - `mood` (0–1): optional, set by the assistant layer
- **C3** Idle has micro-motion (breathing, blink, occasional glance).
- **C4** Transitions are interruptible; a new state never waits for the current animation.
- **C5** Collapsed bubble and expanded avatar are one artboard at two scales.

### 2.3 Conversation panel
- **P1** Compose bottom-sheet inside the overlay window (~60% height, resizable).
- **P2** Message list: user/champi turns, streamed tokens, collapsed action cards (e.g. a running timer card with an undo affordance). A routing/locality footer line is shown per turn (e.g. `whisper.cpp base → local llm → kokoro · 0.9 s`); when the routing policy (§3.3) rejected the edge LLM and sent a turn remote, the footer states why (e.g. size) so the user understands the cost/quality tradeoff without opening settings.
- **P3** Input row: text field, mic (tap = toggle, hold = push-to-talk), attach (image, file, share-sheet payload).
- **P4** Streaming TTS with a visible stop; character lip-syncs via `level`. The panel header shows the active state tag and a provider/locality tag (e.g. `edge · kokoro`, or a remote badge when routed remote).
- **P5** Conversation persists locally; reopening shows where you left off.
- **P6** Openable from: bubble tap, wake word, notification action, launcher icon, Quick Settings tile.

### 2.4 Voice pipeline
Each stage is a provider slot. Edge is the default; remote is used only when the routing policy (§3.3) says the phone can't do the job well enough.

| Stage | Edge provider (default) | Remote provider (heavy) |
|---|---|---|
| Wake word | On-device (openWakeWord ONNX or Porcupine) | — never remote |
| VAD / endpointing | On-device (Silero VAD) | — never remote |
| STT | On-device (whisper.cpp small/base) | Larger STT model |
| LLM | On-device small model for short/simple turns and local intents | Full-size model for everything else |
| TTS | On-device (Kokoro) | Higher-quality voice |

- **V1** Wake word and VAD never leave the device. Audio only leaves the device after wake or explicit mic press, and only if the routing policy picked a remote provider. Onboarding (§6) and settings (§6) must state this plainly.
- **V2** Barge-in: user speech during TTS stops playback and starts a new turn.
- **V3** Android's mic indicator is never suppressed or worked around.
- **V4** Configurable: wake word on/off, push-to-talk only, mute schedule, "edge only" mode (remote providers disabled).

### 2.5 Proactive behaviour
- **N1** The assistant layer can raise a notification; the character enters `notifying` and the bubble pulses. Silent unless flagged urgent.
- **N2** Mirrored to the system tray with reply actions (works when overlay is hidden or the phone is locked).
- **N3** Client-side rate limit on proactive interrupts, user-tunable.

### 2.6 Device actions
Actions champi can take on the phone, each behind a per-action toggle, destructive ones behind a confirmation:
- Alarms/timers, calendar events, SMS drafts (confirm), calls (confirm), open apps/deeplinks, flashlight/DND/Wi-Fi toggles, clipboard read (on request only), share location.

### 2.7 Context input
- Share-sheet target: text, links, images, files into the current conversation.
- Camera capture from the panel.
- Optional periodic context (coarse location, battery, connectivity, foreground app name) — opt-in, off by default.

---

## 3. Architecture

### 3.1 Modules (Gradle)

```
:app              — Application, DI graph, settings UI, onboarding
:overlay          — WindowManager overlay, Compose hosting, bubble/panel UI
:character        — Rive integration, AppState → state-machine input bridge
:assistant        — conversation state, turn orchestration, routing policy, memory
:providers:api    — provider interfaces (below)
:providers:edge   — on-device implementations
:providers:remote — remote implementations (contract only; transport out of scope)
:audio            — capture, playback queue, wake word, VAD
:actions          — device capabilities, permission gating, confirmations
:context          — share-sheet receiver, optional sensors
:core             — models, flows, logging, crypto, persistence
```

### 3.2 Provider interfaces
All in `:providers:api`. Every stage the assistant depends on is one of these; nothing in `:assistant` or the UI knows whether an implementation is edge or remote.

```kotlin
interface WakeWordProvider { fun listen(audio: Flow<PcmFrame>): Flow<WakeEvent> }
interface VadProvider      { fun segment(audio: Flow<PcmFrame>): Flow<SpeechSegment> }
interface SttProvider      { fun transcribe(segment: Flow<PcmFrame>): Flow<Transcript>  /* partial + final */ }
interface LlmProvider      { fun complete(ctx: Conversation, tools: List<ToolSpec>): Flow<LlmEvent> /* token | toolCall | done */ }
interface TtsProvider      { fun synthesize(text: Flow<String>): Flow<AudioChunk> }
interface ActionProvider   { val specs: List<ToolSpec>; suspend fun invoke(call: ToolCall): ToolResult }

interface Provider {
    val id: String
    val locality: Locality          // EDGE | REMOTE
    val cost: Cost                  // latency class, battery class
    suspend fun available(): Boolean
}
```

Each provider also reports `capabilities` (languages, max input length, streaming yes/no) so the router can choose without special-casing.

### 3.3 Routing policy (edge-first)
Lives in `:assistant`. Per request it picks a provider for each stage:

1. If the user set **edge only** → edge, always.
2. If the edge provider is `available()` and the request fits its declared capabilities → edge.
3. Otherwise, if a remote provider is available → remote.
4. Otherwise → degrade: local intents only, queue the turn, character shows `error` briefly then `idle`.

"Fits" for the LLM stage is a small heuristic to start (input length, whether tools are needed, whether the last edge answer was rejected by the user) and can later be a learned classifier. Every routing decision is logged locally so the heuristic can be tuned from real usage. The panel surfaces this decision to the user via the P2/P4 routing footer and header tag.

### 3.4 Runtime structure
- **`ChampiService`** — foreground service (`foregroundServiceType="microphone|specialUse"`). Owns the overlay window and the audio pipeline. Started from the foreground (Android 11+ background-mic restriction).
- **Overlay hosting** — `ComposeView` on `WindowManager` with manually provided `LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner`.
- **State** — single `AppState` `StateFlow` (character state, conversation, audio, routing status), unidirectional data flow into overlay, character, and tray notification.
- **Persistence** — DataStore for settings; Room for conversation and queued turns; Keystore for any provider secrets.

---

## 4. Non-functional Requirements

| Area | Target |
|---|---|
| Wake → listening indicator | ≤ 150 ms |
| End of speech → first TTS audio, edge path | ≤ 1.5 s p50 |
| End of speech → first TTS audio, remote path | ≤ 2.5 s p50 |
| Idle battery (overlay + wake word) | ≤ 3 % / 24 h on a mid-range device |
| Overlay frame budget | 60 fps; Rive idle ≤ 2 % CPU |
| Resident memory, service only | ≤ 120 MB (edge STT/LLM models loaded lazily, unloaded on memory pressure) |
| Cold start → bubble visible | ≤ 800 ms |
| Crash-free sessions | ≥ 99.5 % |

- Offline: wake, VAD, edge STT, edge LLM, edge TTS, local intents, and queued turns all work with no network.
- Privacy: no audio retained beyond the streaming buffer; no third-party analytics; audio and text leave the device only through a remote provider the user enabled.
- Accessibility: TalkBack labels on bubble and panel; panel fully usable without voice.
- Localization: es-MX and en-US at launch.

---

## 5. Platform

- **minSdk 29**, **targetSdk** current.
- **Permissions**

  | Permission | When requested | Required |
  |---|---|---|
  | `SYSTEM_ALERT_WINDOW` | Onboarding, with explanation | Yes |
  | `RECORD_AUDIO` | First mic use | Yes |
  | `POST_NOTIFICATIONS` | Onboarding | Yes |
  | `FOREGROUND_SERVICE`, `_MICROPHONE`, `_SPECIAL_USE` | Manifest | Yes |
  | `ACCESS_COARSE_LOCATION` | On enabling location context | No |
  | `CAMERA` | First camera use | No |
  | `SCHEDULE_EXACT_ALARM` | First alarm action | No |
  | `READ_CONTACTS`, `SEND_SMS`, `CALL_PHONE` | Per action toggle | No |

- **Battery**: ask for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only after the service has been killed once; document OEM autostart quirks.
- **Edge model storage**: models downloaded on first use to app-private storage, versioned, deletable from settings; app install stays small.
- **Distribution**: sideload / self-hosted APK first; Play Store later (overlay + mic + special-use FGS declaration forms).

---

## 6. Visual & Interaction Spec

Design system: "Modernist" — flat, architectural, Archivo typeface throughout, near-mono red accent (`#ec3013`) on off-white (`#f3f2f2`), zero corner radius everywhere except the bubble itself, strong 2px dividers, flush-left labels including inside buttons. The bubble and panel avatar are the one deliberate break from the system's own token discipline — circular, per B1/B6/C1 — because the character must read as alive, not architectural.

### 6.1 Bubble & character states
Rive artboard `champi_mushroom`, state machine `main`, inputs per §2.2 (`state`, `level`, `attention`, `mood`). Seven `state` values, each with a distinct visual treatment:

| State | Trigger | Visual |
|---|---|---|
| `idle` | Default, no active turn | Breathing, blinking, occasional glance (C3) |
| `listening` | Mic open (wake word or press) | Ring radius driven by `level` |
| `thinking` | Turn submitted, routing/inference in flight | Eyes up, animated dots; panel shows a status line when applicable (e.g. "leyendo 2 adjuntos") |
| `speaking` | TTS playing | Body/jaw squash driven by `level`; no per-phoneme mouth shapes in v1 |
| `notifying` | Proactive notification (N1) | Bubble pulses; silent unless flagged urgent |
| `error` | Routing degrade or failure | Brief, then falls back to `idle` |
| `sleeping` | DND schedule active or user-invoked via quick action | Dormant treatment |

Collapsed bubble ≈ 56 dp (B7), hit target ≥ 48 dp. Peek state (B8): bubble tucks 28 dp under the snapped edge after N idle minutes. Expanded avatar and collapsed bubble share one artboard at two scales (C5); avatar renders at 96 dp in the panel header (P4).

### 6.2 Conversation panel
Material 3 shell, follows system theme (light/dark), localized copy (es-MX, en-US). Bottom sheet ~60% height (P1). Header: 96 dp avatar, state tag, provider/locality tag (e.g. `edge · kokoro`, or a remote badge when routed remote), stop button. Message list: user turns right-aligned, champi turns left-aligned, inline collapsed action cards (e.g. a running timer card with undo). Routing/locality footer line per turn (e.g. `whisper.cpp base → local llm → kokoro · 0.9 s`), with a routing-explanation variant when a turn was rejected by the edge LLM for size and sent remote (§3.3, P2). Input row: attach / text / mic (P3).

### 6.3 Long-press quick actions (B5)
Two interaction geometries were explored during design and neither is committed — see §7 for the decision to make:
- **Radial arc**: hold ≥ 400 ms, 4 targets on a 128 dp arc swept away from the snapped edge; release-on-target fires it, release elsewhere cancels; finger position drives `attention`.
- **Edge rail**: single-column list sliding out from the snapped edge, stays open until tap-outside, 48 dp rows; noted during design review as easier for TalkBack.

Both variants surface the same four actions: mute mic, push-to-talk (hold), sleep, settings.

### 6.4 Onboarding
Two flows were explored and neither is committed — see §7:
- **Checklist**: one screen listing the three required permissions (notifications, overlay, mic deferred to first use) with per-row status and a single Continue action.
- **Stepper**: one permission per step (3 steps), character/screen demonstrates what each permission unlocks before the system prompt fires.

Hard requirement for either flow: onboarding must state plainly that wake word and VAD stay on-device, and that audio only leaves the device after wake or a mic press, and only if the user has enabled a remote provider (V1). This is required onboarding copy, not optional polish.

### 6.5 Settings
Two structures were explored and neither is committed — see §7:
- **Flat grouped list**: Voice (wake word, push-to-talk-only, mute schedule, earcons), Providers (STT/LLM/TTS rows each tagged edge/remote/edge→remote, downloaded-model management), Actions (per-action toggles), Bubble (peek timing, proactive-interrupt rate limit).
- **Provider-pipeline table**: one row per pipeline stage (wake word, VAD, STT, LLM, TTS) with Edge/Remote columns showing `always` / `default` / `off` / `never` per stage, plus an "edge only" master toggle at the top.

Both must make clear that wake word and VAD are edge-only with no remote column value (V1).

### 6.6 Sound
Optional earcons on wake and end-of-turn, off by default.

---

## 7. Open Questions

1. Wake word engine: openWakeWord (open, trainable, needs ONNX runtime) vs Porcupine (polished, licence). Custom "hey champi" model either way.
2. Edge LLM: which small model, which runtime (llama.cpp via JNI, MediaPipe LLM Inference, ExecuTorch), and what memory footprint is acceptable on the target devices.
3. ~~Edge STT: whisper.cpp vs Android's on-device `SpeechRecognizer`.~~ **Resolved: whisper.cpp**, via JNI (see issue #26).
4. ~~Edge TTS: platform `TextToSpeech` vs Piper.~~ **Resolved: Kokoro** — neither of the two options originally weighed here (see issue #27).
5. Routing heuristic thresholds — what makes a turn "heavy" enough for the remote LLM. Start conservative (edge handles short chit-chat and local intents only) and widen from logs.
6. Conversation memory: what the edge LLM sees as context vs what the remote LLM sees, and who owns long-term memory.
7. Quick-actions geometry (§6.3): radial arc vs edge rail. Arc reads livelier and ties more directly into `attention`; rail is easier for TalkBack. Needs a decision before M1.
8. Onboarding flow (§6.4): single checklist screen vs three-step stepper. Checklist is faster; stepper demonstrates value before each prompt. Needs a decision before M0/M1.
9. Settings structure (§6.5): flat grouped list vs provider-pipeline table. Table is more transparent about edge/remote routing per stage; flat list is more conventional. Needs a decision before M4 (when routing/provider settings become user-facing).

---

## 8. Milestones

| # | Deliverable |
|---|---|
| M0 | Project skeleton, DI, foreground service showing a static bubble overlay |
| M1 | Rive character with all states; drag/snap/dismiss; expand to empty panel |
| M2 | Provider interfaces + text-only loop with an edge LLM provider |
| M3 | Voice on edge: wake word, VAD, edge STT, edge TTS, barge-in |
| M4 | Routing policy + remote provider slots (stubbed), edge-only mode, queued turns |
| M5 | Notifications + first device actions (alarm, timer, calendar) |
| M6 | Share-sheet, camera, context opt-ins; battery/latency profiling pass |
| M7 | Hardening, es-MX/en-US, self-hosted release channel |
