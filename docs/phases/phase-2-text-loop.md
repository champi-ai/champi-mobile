# Phase 2: Provider interfaces + text-only loop

## Goal
The user can type a message in the panel, an on-device LLM generates a streamed response, and the conversation persists across reopens.

## Spec references
P2, P3, P5, V4 (edge-only default)

## Deliverables

### Overlay / UI surface
- [ ] Message list in the conversation panel: user turns right-aligned, champi turns left-aligned, streamed tokens (`:overlay`)
- [ ] Input row: text field with send action (`:overlay`)
- [ ] Character state driven by turn lifecycle: `idle` -> `thinking` -> `speaking` (text display) -> `idle` (`:overlay`)

### Assistant / orchestration
- [ ] `ConversationManager` — manages turns, appends user/assistant messages, persists to Room (`:assistant`)
- [ ] `TurnOrchestrator` — receives user input, calls LLM provider, emits streamed tokens, updates `AppState` (`:assistant`)
- [ ] Conversation persistence: Room database for messages, reopening the panel shows where you left off (`:assistant` + `:core`)

### Providers
- [ ] Provider interfaces in `:providers:api`: `LlmProvider`, `Provider` base, `Locality`, `Cost`, `ToolSpec`, `ToolCall`, `ToolResult`
- [ ] Edge LLM provider implementation: small on-device model via llama.cpp JNI or MediaPipe LLM Inference (`:providers:edge`) — open question 2 must be resolved for runtime choice
- [ ] Model download-on-first-use to app-private storage, with progress UI (`:providers:edge`)
- [ ] Stub `SttProvider`, `TtsProvider`, `WakeWordProvider`, `VadProvider`, `ActionProvider` interfaces defined but not implemented (`:providers:api`)

### Infrastructure
- [ ] Room database schema: conversations, messages with role/content/timestamp/provider metadata (`:core`)
- [ ] Model storage and versioning utilities (`:core`)

## Done Definition
- Typing a message and pressing send shows the user message in the panel
- The character transitions to `thinking`, then streamed tokens appear as a champi response
- The full response renders left-aligned once streaming completes, character returns to `idle`
- Closing and reopening the panel (or killing the app) shows the prior conversation
- The edge LLM model downloads on first use with visible progress
- No network is required for the text loop to function

## Parallel work
- Provider interface design (`:providers:api`) can start as soon as Phase 1 freezes the `AppState` contract
- Room schema work in `:core` is independent of LLM integration
- Panel message-list UI can be built against fake data while the LLM provider is being integrated

## Phase dependencies
- Requires: Phase 1 (conversation panel shell, character state bridge, `AppState` StateFlow)

## Complexity
- Overlay/UI: M
- Character: S
- Assistant/Providers: XL
- Infra: M

## Risks
- Edge LLM runtime choice (llama.cpp vs MediaPipe vs ExecuTorch) affects memory, latency, and model compatibility — needs early prototyping
- On-device model memory footprint may exceed the 120 MB resident target on low-RAM devices
- Model download size and first-use experience: users may abandon if the initial download is too large or slow
