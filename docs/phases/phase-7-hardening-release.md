# Phase 7: Hardening, localization, release

## Goal
The app is localized in es-MX and en-US, crash-free sessions reach 99.5%+, accessibility passes, and a self-hosted APK release channel is operational.

## Spec references
B6 (DND schedule), P6 (all entry points), V1 (onboarding privacy copy), V4 (mute schedule)

## Deliverables

### Overlay / UI surface
- [ ] Onboarding flow (checklist or stepper per open question 8 decision) with privacy disclosure: wake word and VAD stay on-device, audio only leaves device after wake/press and only if remote provider is enabled (`:app`)
- [ ] Full settings screen: voice settings (wake word on/off, push-to-talk only, mute schedule, earcons), provider management, action toggles, bubble peek timing, proactive-interrupt rate limit (`:app`)
- [ ] DND schedule: bubble auto-hides and wake word suspends during configured quiet hours (`:overlay`)
- [ ] TalkBack labels on bubble, panel, quick actions, and all interactive elements (`:overlay`)
- [ ] Panel fully usable without voice (text-only path with keyboard) (`:overlay`)

### Character / rendering
- [ ] `sleeping` state visual treatment activated by DND schedule or sleep quick action (`:character`)
- [ ] Earcons: optional sounds on wake and end-of-turn, off by default (`:audio`)

### Assistant / orchestration
- [ ] Conversation memory management: context windowing for edge vs remote LLM (`:assistant`) — open question 6 informs scope

### Infrastructure
- [ ] es-MX and en-US string localization for all UI copy (all modules)
- [ ] Crash reporting and crash-free session tracking (`:app`)
- [ ] Crash-free sessions target: >= 99.5%
- [ ] `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt: shown only after the service has been killed once (`:app`)
- [ ] OEM autostart quirk documentation and in-app guidance (`:app`)
- [ ] Self-hosted APK release channel: signed builds, update check mechanism (`:app`)
- [ ] Edge model lifecycle: models deletable from settings, versioned, lazy-loaded, unloaded on memory pressure (`:providers:edge`)
- [ ] No third-party analytics; no audio retained beyond the streaming buffer (privacy compliance) (all modules)

## Done Definition
- The app is fully usable in both es-MX and en-US; switching device language updates all UI strings
- Onboarding flow grants required permissions and displays the privacy disclosure before the service starts
- TalkBack can navigate and activate every interactive element (bubble, panel, quick actions, settings)
- DND schedule hides the bubble and suspends wake word during quiet hours; character shows `sleeping`
- Crash-free session rate is >= 99.5% over a multi-day dogfood period
- A signed APK is downloadable from the self-hosted channel and installs cleanly on a fresh device
- The app checks for updates from the self-hosted channel

## Parallel work
- Localization (string extraction + translation) can run in parallel with crash hardening
- Accessibility audit and fixes can run in parallel with release infrastructure
- Self-hosted release channel setup is independent of all feature work

## Phase dependencies
- Requires: Phase 6 (all features complete, profiling done, performance budgets met or documented)

## Complexity
- Overlay/UI: L
- Character: S
- Assistant/Providers: M
- Infra: L

## Risks
- Achieving 99.5% crash-free on the diversity of Android 10+ devices requires broad testing
- OEM autostart/battery quirks are a long tail; documentation helps but cannot solve all devices
- Self-hosted update mechanism must handle signature verification and downgrade protection
- Localization of LLM-generated content depends on model capabilities, not just UI strings
