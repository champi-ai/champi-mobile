# Phase 6: Share-sheet, camera, context + profiling

## Goal
Users can share content into champi from any app, capture images from the panel, opt in to periodic context, and the app meets its battery and latency targets.

## Spec references
P3 (attach: image, file, share-sheet payload), P6 (launcher icon open)

## Deliverables

### Overlay / UI surface
- [ ] Attach button in input row: image picker, file picker, share-sheet payload display (`:overlay`)
- [ ] Camera capture from the panel (`:overlay`)
- [ ] Launcher icon opens the panel (`:app`)

### Assistant / orchestration
- [ ] Multi-modal turn support: images and files as conversation context alongside text (`:assistant`)

### Providers
- [ ] Share-sheet receiver: text, links, images, files routed into the current conversation (`:context`)
- [ ] Optional periodic context provider: coarse location, battery, connectivity, foreground app name (`:context`)
- [ ] Context opt-in settings: off by default, per-signal toggles (`:context`)

### Infrastructure
- [ ] `ACCESS_COARSE_LOCATION` permission request on enabling location context (`:app`)
- [ ] `CAMERA` permission request on first camera use (`:app`)
- [ ] Battery profiling: measure idle drain (overlay + wake word) against the 3% / 24h target (`:core`)
- [ ] Latency profiling: measure end-of-speech to first TTS audio against 1.5 s p50 edge / 2.5 s p50 remote targets (`:core`)
- [ ] Memory profiling: verify resident memory <= 120 MB with lazy model loading and memory-pressure unloading (`:core`)
- [ ] Rive idle CPU verification: <= 2% CPU (`:character`)
- [ ] Cold start measurement: bubble visible <= 800 ms (`:app`)

## Done Definition
- Sharing text/link/image from another app via the share sheet opens champi with the content in the conversation
- Tapping the attach button allows picking an image or file; the content appears in the conversation
- Camera capture from the panel attaches a photo to the current turn
- Optional context (location, battery, etc.) is off by default; enabling it requests the appropriate permission and surfaces context in the assistant's input
- Battery drain in idle (overlay + wake word active, screen off) is measured and documented against the 3% / 24h target
- End-to-end voice latency is measured and documented against p50 targets
- Resident memory with all edge models loaded is measured against the 120 MB target

## Parallel work
- Share-sheet receiver (`:context`) is independent of camera/attach UI (`:overlay`)
- Profiling passes are independent of feature work and can run on any device fleet in parallel

## Phase dependencies
- Requires: Phase 3 (voice pipeline for latency profiling)
- Requires: Phase 5 (full feature set active for realistic battery/memory profiling)

## Complexity
- Overlay/UI: M
- Character: none
- Assistant/Providers: M
- Infra: L

## Risks
- Battery profiling results are device-dependent; meeting 3% / 24h on mid-range devices may require wake-word duty cycling or model unloading
- Share-sheet intent handling across OEMs is inconsistent (MIME types, URI permissions, content provider access)
- Camera capture inside an overlay window may conflict with other apps' camera use
