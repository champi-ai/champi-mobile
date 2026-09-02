# Phase 4: Routing policy + remote provider slots

## Goal
The assistant selects edge or remote providers per turn based on a routing policy, supports an edge-only mode toggle, and queues turns when no provider is available.

## Spec references
P2 (routing footer explanation), P4 (remote badge), V4 (edge-only mode)

## Deliverables

### Overlay / UI surface
- [ ] Remote badge in panel header when a turn is routed remote (`:overlay`)
- [ ] Routing explanation variant in turn footer when edge LLM rejected a turn for size and sent it remote (`:overlay`)
- [ ] Settings screen: provider pipeline view (STT/LLM/TTS rows with edge/remote tags, downloaded-model management), edge-only master toggle (`:app`) — settings structure decision (open question 9) must be made before this phase

### Assistant / orchestration
- [ ] `RoutingPolicy` implementation in `:assistant`: edge-first decision logic per the 4-step algorithm (user override -> edge available + fits -> remote available -> degrade)
- [ ] "Fits" heuristic for LLM stage: input length, tool requirements, user rejection of prior edge answer (`:assistant`)
- [ ] Routing decision logging for heuristic tuning (`:assistant`)
- [ ] Turn queuing: when no provider is available, queue the turn and replay when connectivity/availability returns (`:assistant`)
- [ ] Degraded mode: local intents only, character shows `error` briefly then `idle` (`:assistant`)

### Providers
- [ ] `SttProvider` remote stub (contract only, transport out of scope) (`:providers:remote`)
- [ ] `LlmProvider` remote stub (`:providers:remote`)
- [ ] `TtsProvider` remote stub (`:providers:remote`)
- [ ] Provider `capabilities` reporting: languages, max input length, streaming support (`:providers:api`)
- [ ] Provider `available()` implementation reflecting model load state and network reachability (`:providers:edge`, `:providers:remote`)

### Infrastructure
- [ ] DataStore settings for edge-only mode, per-provider enable/disable (`:core`)
- [ ] Room schema for queued turns (`:core`)

## Done Definition
- With edge-only mode on, all turns route to edge providers regardless of input complexity
- With edge-only mode off and a remote stub registered, a long/complex input shows the remote badge and routing explanation in the footer
- When no provider is available (airplane mode + edge model unloaded), the turn is queued, character shows `error`, and the turn replays when a provider becomes available
- Settings screen shows provider rows with edge/remote tags and the edge-only toggle
- Routing decisions are logged and inspectable (via logcat or a debug screen)

## Parallel work
- Settings UI (`:app`) can be built in parallel with routing policy logic (`:assistant`)
- Remote provider stubs (`:providers:remote`) are independent of routing policy implementation

## Phase dependencies
- Requires: Phase 3 (voice pipeline, all edge providers implemented, per-turn routing metadata)

## Complexity
- Overlay/UI: M
- Character: none
- Assistant/Providers: L
- Infra: M

## Risks
- "Fits" heuristic thresholds (open question 5) are guesses initially; need real usage data to tune
- Queued turn replay must handle stale context gracefully (conversation may have moved on)
- Remote provider stubs are contracts only — actual transport is out of scope, so integration testing is limited to interface compliance
