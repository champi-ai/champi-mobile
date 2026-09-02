# Build rounds

Issue priority for a personal, thin-client build of champi-android. The phone does wake word and VAD on device and streams everything else to the champi brain on your own infrastructure. Labels `round:1` and `round:2` on GitHub mirror this file.

**Round 1** builds the always-on client: overlay skeleton, character and interaction, text loop against the brain, on-device wake word and VAD with server STT and TTS, barge-in, proactive notifications, and the share sheet as an idea inbox. 31 issues.

**Round 2** is everything that serves strangers, offline use, or polish: on-device models, edge-first routing, profiling, localization, crash reporting, release channel, accessibility, and device actions. 27 issues.

## Round 1

| Issue | Phase | Title |
|---|---|---|
| #3 | M0 | [INFRA]: Gradle multi-module scaffold |
| #4 | M0 | [INFRA]: Hilt/Dagger DI graph wired across modules |
| #5 | M0 | [INFRA]: Manifest permissions, foreground service declarations, and boot receiver |
| #6 | M0 | [INFRA]: CI pipeline — assembleDebug and lint on pull requests |
| #7 | M0 | [OVERLAY]: WindowManager overlay host and ComposeView lifecycle wiring |
| #8 | M0 | [APP]: ChampiService foreground service, persistent notification, and runtime permission requests |
| #9 | M1 | [INFRA]: AppState StateFlow definition and DataStore setup |
| #10 | M1 | [OVERLAY]: Drag gesture, snap-to-edge, and position persistence |
| #11 | M1 | [OVERLAY]: Dismiss zone and peek state |
| #12 | M1 | [OVERLAY]: Conversation panel shell — bottom sheet expand, collapse, and IME avoidance |
| #13 | M1 | [OVERLAY]: Long-press quick-actions surface |
| #14 | M1 | [CHARACTER]: Rive artboard integration and AppState-to-state-machine input bridge |
| #15 | M1 | [CHARACTER]: Dual-scale rendering from one artboard and interruptible state transitions |
| #16 | M2 | [INFRA]: Room database schema — conversations and messages, plus model storage utilities |
| #17 | M2 | [PROVIDERS]: Provider API interface definitions |
| #19 | M2 | [ASSISTANT]: ConversationManager — turn management and Room persistence |
| #20 | M2 | [ASSISTANT]: TurnOrchestrator — text input to streamed LLM response pipeline |
| #21 | M2 | [OVERLAY]: Conversation panel message list and input row |
| #22 | M2 | [OVERLAY]: Character state wiring for text turn lifecycle in the panel |
| #23 | M3 | [INFRA]: :audio module — AudioCapture, PlaybackQueue, and RECORD_AUDIO permission |
| #24 | M3 | [PROVIDERS]: WakeWordProvider edge implementation |
| #25 | M3 | [PROVIDERS]: VadProvider edge implementation — Silero VAD |
| #28 | M3 | [ASSISTANT]: VoiceTurnOrchestrator — wake-to-TTS pipeline and per-turn routing metadata |
| #29 | M3 | [ASSISTANT]: Barge-in — interrupt TTS playback on user speech onset |
| #30 | M3 | [CHARACTER]: Level input driven by mic amplitude and TTS playback amplitude |
| #31 | M3 | [OVERLAY]: Voice panel controls — mic button, TTS stop, routing footer, and provider header tag |
| #38 | M5 | [INFRA]: System tray notification with reply actions, Quick Settings tile, and DataStore for action/rate-limit settings |
| #39 | M5 | [ASSISTANT]: Proactive notification engine and client-side rate limiter |
| #43 | M5 | [CHARACTER]: notifying state activation — bubble pulse on proactive notification |
| #44 | M5 | [OVERLAY]: Notification action open, inline action cards, and destructive action confirmation dialog |
| #46 | M6 | [PROVIDERS]: Share-sheet receiver — text, links, images, and files into the conversation |

Note on #19 to #22: the `LlmProvider` behind the text loop is a brain client over WebSocket, not the edge LLM from #18. Note on #28 and #29: STT and TTS stages stream to and from the server; only wake word (#24) and VAD (#25) run on the phone.

## Round 2

| Issue | Phase | Title | Why it waits |
|---|---|---|---|
| #18 | M2 | [PROVIDERS]: Edge LLM provider — on-device model integration and download-on-first-use | On-device LLM. The brain runs the model on your GPUs or Claude. |
| #26 | M3 | [PROVIDERS]: SttProvider edge implementation — whisper.cpp or Android on-device SpeechRecognizer | On-device STT. champi-stt streams Whisper from the server. |
| #27 | M3 | [PROVIDERS]: TtsProvider edge implementation — Android TextToSpeech or Piper | On-device neural TTS. champi-tts serves Kokoro from the server. |
| #32 | M4 | [INFRA]: DataStore settings for routing and queued turns Room schema | Routing settings and queued-turn schema only matter with two LLM tiers. |
| #33 | M4 | [PROVIDERS]: Remote provider stubs and provider capabilities reporting | Remote stubs are replaced by the real brain client in round 1. |
| #34 | M4 | [ASSISTANT]: RoutingPolicy — edge-first provider selection, fits heuristic, and decision logging | Edge-first routing heuristic. Thin client has one route. |
| #35 | M4 | [ASSISTANT]: Turn queuing and degraded mode | Turn queuing and degraded mode. Replace with a simple offline banner later. |
| #36 | M4 | [OVERLAY]: Remote badge in panel header and routing explanation in turn footer | Remote badge and routing explanation. Everything is remote by design. |
| #37 | M4 | [APP]: Settings screen — provider pipeline, downloaded model management, and edge-only toggle | Provider pipeline settings. No provider choice in the thin client. |
| #40 | M5 | [ASSISTANT]: Tool-call flow — LLM toolCall events routed through ActionProvider to ToolResult | Tool calls execute in the brain. Phone-side action dispatch comes later. |
| #41 | M5 | [ACTIONS]: ActionProvider for alarms and timers | Alarm and timer actions. Useful, but depends on #40. |
| #42 | M5 | [ACTIONS]: ActionProvider for calendar events | Calendar actions. Depends on #40. |
| #45 | M6 | [INFRA]: Performance profiling — battery, latency, memory, Rive CPU, and cold start | Formal profiling pass against public-release targets. |
| #47 | M6 | [PROVIDERS]: Optional periodic context provider — location, battery, connectivity, and foreground app | Periodic context signals. Add once the brain has a use for them. |
| #48 | M6 | [ASSISTANT]: Multi-modal turn support — images and files as conversation context | Multi-modal turns. Add after text and voice are solid. |
| #49 | M6 | [OVERLAY]: Attach button — image picker, file picker, camera capture, and share-sheet payload display | Attach button and camera. Share sheet (#46) covers idea capture first. |
| #50 | M6 | [APP]: Launcher icon opens panel, CAMERA permission, and ACCESS_COARSE_LOCATION permission wiring | Launcher entry and camera and location permissions. Follows #47 and #49. |
| #51 | M7 | [INFRA]: es-MX and en-US string localization across all modules | Two-locale localization. Ship in your language only. |
| #52 | M7 | [INFRA]: Crash reporting and crash-free session tracking | Crash reporter. Logcat is enough for a handful of users. |
| #53 | M7 | [INFRA]: Self-hosted APK release channel — signed builds, update check, and OEM battery guidance | Self-hosted release channel and OEM guidance. Sideload the APK. |
| #54 | M7 | [INFRA]: Edge model lifecycle — deletable from settings, versioned, lazy-loaded, memory-pressure unloading, and privacy compliance | Edge model lifecycle. No edge models in round 1. |
| #55 | M7 | [APP]: Onboarding flow with required privacy disclosure | Full onboarding flow. The basic permission screen from #8 is enough. |
| #56 | M7 | [APP]: Full settings screen — voice, providers, actions, bubble, and proactive-interrupt settings | Full settings screen. Round 1 needs only server address and wake word toggle. |
| #57 | M7 | [OVERLAY]: DND schedule — bubble auto-hide and wake word suspension during quiet hours | DND schedule and full-screen detection. Comfort feature. |
| #58 | M7 | [OVERLAY]: TalkBack accessibility — labels on bubble, panel, quick actions, and all interactive elements | TalkBack pass. Only if someone in the group needs it. |
| #59 | M7 | [CHARACTER]: sleeping state visual treatment and earcons | Sleeping state and earcons. Polish. |
| #60 | M7 | [ASSISTANT]: Conversation memory management — context windowing for edge and remote LLM | Context windowing. The brain owns conversation memory. |

## Applying the labels

Run `scripts/label-rounds.sh` with a `gh` login that can write issues. It creates the two labels if missing and adds one to each issue without touching existing labels.
