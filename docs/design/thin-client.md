# Champi Thin Client

**Status:** proposed redesign · **Date:** 2026-09-02 · **Supersedes:** the edge-first routing in [`docs/specs/mobile.md`](../specs/mobile.md) §2.4, §3.3 for round 1

The phone keeps its ears and its face. Everything that thinks, remembers, listens closely, or acts on your machines moves to a brain running where your GPUs are. One WebSocket replaces the whole on-device model stack.

A styled version of this document with the same figures is at [`thin-client.html`](./thin-client.html).

| | |
|---|---|
| Round 1 | 31 issues · thin client |
| Round 2 | 27 issues · deferred |
| Stays on phone | Wake word · VAD · Rive · tray |
| Moves to brain | STT · LLM · TTS · memory · tools |

The issue split is in [`docs/rounds.md`](../rounds.md).

---

## 01 · What changes

The v0.3 spec put five inference stages and a routing policy inside the phone and left remote providers as stubs with transport out of scope. For a personal Jarvis the transport *is* the product, and the heavy stages already exist as champi-stt, champi-tts, and rc-mcp on your infrastructure.

![Before: spec v0.3 phone with wake word, VAD, STT, LLM, TTS, routing and memory, plus an unavailable remote stub. After: phone with wake word, VAD, Rive bubble and tray linked by one WebSocket to a brain with gateway, STT, Claude, TTS, memory, rc-mcp tools and monitors.](./figures/01-before-after.svg)

**The one edge that changes everything.** Left, the spec's phone carries seven subsystems and a routing heuristic to decide between them. Right, four stay on the phone and a single authenticated WebSocket carries PCM and text up, audio and tokens down. The red-outlined gateway is the only new server component the phone knows about.

| Spec v0.3 | Thin client |
|---|---|
| Edge-first routing with a fits heuristic, queue, and edge-only mode | **Remote-first, one route.** Offline shows a banner and buffers text; that is round 2. |
| 120 MB resident memory budget for five models | **Phone stays under 60 MB** with only wake word and VAD loaded. Battery still matters because the mic loop is always on. |
| `:providers:remote` is a stub, transport out of scope | **`:providers:remote` becomes the main provider.** One `BrainClient` satisfies `SttProvider`, `LlmProvider` and `TtsProvider` over the same socket. |
| Context windowing on the phone | **The brain owns memory.** Ideas, commands and conversation live server-side. The phone keeps a local transcript cache so the panel reopens where you left it. |
| Multi-step privacy onboarding | **One sentence in settings.** Audio leaves the phone only after wake or mic press, and only to your own server over Tailscale. |

---

## 02 · Data flow

Three tiers. The phone produces PCM, text, and shared items and consumes tokens, audio, and pushes. The brain brokers streams and runs the agent loop. Your infrastructure does the heavy lifting and is reachable only over your private network.

![Three columns: phone, brain, infrastructure. Red arrows carry PCM, text and files from phone to gateway and audio, tokens and notifications back. Gateway exchanges PCM and transcripts with champi-stt, sentences and audio chunks with champi-tts. Agent loop exchanges messages and tokens with the Claude API and sends MCP tool calls to rc-mcp, whose agents reach GPUs, docker and services.](./figures/02-data-flow.svg)

**Red is the only path that leaves the phone.** Everything the phone sends goes to the gateway; everything it shows comes back on the same socket. The gateway streams PCM to champi-stt and sentences to champi-tts so the agent loop only ever sees text. The scheduler wakes the loop on a cron for GPU, service, and repo checks, and anything worth saying comes back to the phone as a notification. Ideas from the share sheet land in memory through the same loop.

| Hop | Carries | Direction |
|---|---|---|
| Phone → Gateway | PCM frames after wake or mic press, typed text, shared files | up |
| Gateway → Phone | Transcript partials, tokens, TTS audio chunks, notifications | down |
| Gateway ↔ champi-stt | PCM stream in, partial and final transcripts out | both |
| Gateway ↔ champi-tts | Sentence chunks in, audio chunks out | both |
| Gateway ↔ Agent loop | Final text and share items in, tokens and pushes out | both |
| Agent loop ↔ Claude API | Messages and tool specs in, tokens and tool calls out | both |
| Agent loop → rc-mcp | MCP tool calls; destructive calls require confirmation | down |
| rc-mcp → machines | Agents dial out from each machine: shell, files, processes, sysinfo | down |
| Scheduler → Agent loop | Cron wake-ups for monitoring checks | in |
| Agent loop ↔ Memory | Recall before a turn, write after; ideas and notes | both |

---

## 03 · One voice turn

Only the first 150 ms happen on the phone. From wake onward the phone streams PCM and plays what comes back. Barge-in is a single cancel that runs down every lane at once.

![Timeline with lanes for phone, gateway, STT, Claude and TTS. Wake word starts PCM streaming; STT returns partials then a final transcript to Claude; Claude streams tokens to TTS and the phone; TTS returns audio the phone plays. When the user speaks over playback, barge-in cancels TTS, tokens and playback and the phone is listening again.](./figures/03-voice-turn.svg)

**The Claude lane stands for the agent loop calling the Claude API.** Partials flow back to the panel while you are still talking, so the transcript is visible before Whisper finalises. TTS starts on the first complete sentence, not the full answer, which is what keeps the 1.5 s target reachable. The dashed red line is one structured-concurrency cancel: playback, synthesis, and generation are children of the same voice scope.

Targets carried over from the spec:

| Measure | Target |
|---|---|
| Wake → `LISTENING` indicator | ≤ 150 ms, on device |
| End of speech → first TTS audio | ≤ 1.5 s p50 over LAN with GPU STT and TTS |
| Barge-in → playback stopped | ≤ 500 ms |

---

## 04 · Module map

The eleven-module layout from the spec survives. Three modules shrink, one flips from stub to centrepiece, and two wait for round 2.

| Module | Round 1 role | Change from spec |
|---|---|---|
| `:app` | Foreground service, permissions, minimal settings: server address, wake word toggle | Full settings and onboarding deferred (round 2) |
| `:overlay` | Bubble, drag and snap, panel, voice controls, notification open | Remote badge and routing footer removed. Header shows connected or offline. |
| `:character` | Rive artboard, seven states, `level` and `attention` inputs | Unchanged |
| `:audio` | AudioCapture, PlaybackQueue, wake word and VAD hosting, amplitude for `level` | Unchanged. Echo cancellation still matters for barge-in. |
| `:providers:api` | Same interfaces | Unchanged. `SttProvider`, `LlmProvider`, `TtsProvider` are now implemented by one class. |
| `:providers:edge` | Wake word and Silero VAD only | Edge LLM, whisper.cpp, Piper deferred (round 2) |
| `:providers:remote` | `BrainClient`: WebSocket, auth, PCM up, audio and token streams down, reconnect | **Flipped.** Was contract only with transport out of scope. Now the main provider. |
| `:assistant` | Turn orchestration, voice turn, barge-in, `AppState`, per-turn latency | Routing policy, fits heuristic, queue, context windowing removed. Brain owns memory. |
| `:actions` | Empty | Alarms, timers, calendar deferred (round 2) |
| `:context` | Share sheet receiver into the conversation | Periodic context signals deferred (round 2) |
| `:core` | `AppState`, DataStore, Room for transcript cache | Model storage and versioning utilities deferred (round 2) |

---

## 05 · Brain components that do not exist yet

champi-stt, champi-tts, and rc-mcp are mature. The current champi orchestrator is a single-turn call with no tools, no history, and no memory. These five pieces turn it into the brain the phone talks to.

| Component | Job | Builds on | Agent-days |
|---|---|---|---|
| gateway | Authenticated WebSocket per phone, stream broker between phone, STT and TTS, push channel | champi-stt web server, champi-ipc | 2 to 3 |
| agent loop | Claude with tool use, confirmations for destructive calls, streaming tokens | Claude API tool runner, rc-mcp as MCP toolset | 3 to 5 |
| memory | Ideas, commands, journal. Every utterance tagged and searchable. Context assembly per turn | SQLite plus embeddings, or the Claude memory tool | 2 to 4 |
| scheduler | Cron-fired checks on GPUs, services, repos. Findings become notifications to the phone | Sonnet 5 for cheap monitors, Opus 5 for the main loop | 2 to 3 |
| stt / tts services | Streaming endpoints around Whisper and Kokoro on the GPU box | champi-stt, champi-tts as they are | 2 to 3 |

Build the brain first. It is usable from the desktop through champi-stt on day one, and every round 1 phone issue then has a real server to talk to instead of a stub.

## Open decision

The phone keeps no LLM at all in round 1, so with no network it shows an offline banner rather than answering. A small on-device fallback moves #18 back into round 1 and adds about a week.
