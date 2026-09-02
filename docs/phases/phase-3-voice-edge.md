# Phase 3: Voice on edge

## Goal
The user can speak to champi via wake word or mic press, hear a spoken response from the on-device TTS, and interrupt playback with barge-in — all without network.

## Spec references
V1, V2, V3, V4, P3, P4, C2 (level input)

## Deliverables

### Overlay / UI surface
- [ ] Mic button in input row: tap toggles listen mode, hold for push-to-talk (`:overlay`)
- [ ] TTS stop button in panel header; visible during `speaking` state (`:overlay`)
- [ ] Routing/locality footer line per turn (e.g. `whisper.cpp base -> local llm -> piper`) (`:overlay`)
- [ ] Provider/locality tag in panel header (e.g. `edge . piper`) (`:overlay`)

### Character / rendering
- [ ] `level` input driven by mic amplitude during `listening` state (`:character`)
- [ ] `level` input driven by TTS playback amplitude during `speaking` state (`:character`)

### Assistant / orchestration
- [ ] `VoiceTurnOrchestrator` — extends turn orchestration to handle audio input/output: wake -> VAD -> STT -> LLM -> TTS pipeline (`:assistant`)
- [ ] Barge-in: user speech during TTS stops playback and starts a new turn (`:assistant`)
- [ ] Per-turn routing metadata logged and surfaced to the panel footer (`:assistant`)

### Providers
- [ ] `WakeWordProvider` edge implementation: openWakeWord ONNX or Porcupine (`:providers:edge`) — open question 1
- [ ] `VadProvider` edge implementation: Silero VAD (`:providers:edge`)
- [ ] `SttProvider` edge implementation: whisper.cpp small/base or Android on-device `SpeechRecognizer` (`:providers:edge`) — open question 3
- [ ] `TtsProvider` edge implementation: Android `TextToSpeech` or Piper (`:providers:edge`) — open question 4

### Infrastructure
- [ ] `:audio` module: `AudioCapture` (mic PCM stream), `PlaybackQueue` (TTS audio chunks), wake-word and VAD hosting
- [ ] `RECORD_AUDIO` runtime permission request on first mic use
- [ ] Android mic indicator compliance (V3): never suppress the system mic-use indicator
- [ ] Model download for wake word, STT, TTS models (same storage/versioning infra from Phase 2)

## Done Definition
- Saying the wake word (or pressing the mic button) activates listening; the character transitions to `listening` with amplitude-driven ring
- Speaking a query produces a transcription (visible in the panel), an LLM response, and spoken TTS output
- During TTS playback the character shows `speaking` with amplitude-driven animation
- Speaking during TTS playback interrupts it (barge-in) and starts a new listening turn
- The Android system mic indicator is visible whenever the mic is active
- Each turn's routing footer shows the provider chain used
- The full voice loop works in airplane mode

## Parallel work
- Wake word + VAD work (`:audio` + `:providers:edge`) can proceed in parallel with STT/TTS provider implementation
- Panel UI updates (mic button, footer, header tags) can be built in parallel with audio pipeline work

## Phase dependencies
- Requires: Phase 2 (provider interfaces, LLM provider, conversation persistence, turn orchestration)

## Complexity
- Overlay/UI: M
- Character: S
- Assistant/Providers: XL
- Infra: L

## Risks
- Wake word accuracy: custom "hey champi" model needs training data and tuning for both es-MX and en-US
- Audio pipeline latency: chaining wake -> VAD -> STT -> LLM -> TTS on-device must meet the 1.5 s p50 target
- Simultaneous mic capture (wake word always-on) and TTS playback requires careful audio focus and echo management
- Memory pressure: loading wake word + VAD + STT + LLM + TTS models concurrently may exceed 120 MB target
