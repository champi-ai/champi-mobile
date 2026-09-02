# Phase 1: Character + interaction

## Goal
The Rive mushroom character renders inside the bubble with idle animation, the bubble is draggable with edge-snap and dismiss, and tapping it expands an empty conversation panel.

## Spec references
B2, B3, B4, B5, B6, B7, B8, C1, C2, C3, C4, C5, P1

## Deliverables

### Overlay / UI surface
- [ ] Touch handling: drag gesture on the bubble, edge-snap on release, position persistence via DataStore (`:overlay`)
- [ ] Dismiss zone: drag to bottom-center hides the bubble until relaunch (`:overlay`)
- [ ] Tap handler: single tap expands to a Compose bottom-sheet panel (~60% height), tap-outside or swipe-down collapses (`:overlay`)
- [ ] Long-press quick-actions surface with the four actions: mute mic (no-op stub), push-to-talk (stub), sleep, settings (stub) (`:overlay`) — geometry decision (radial arc vs edge rail) must be made before this phase starts (open question 7)
- [ ] IME avoidance: bubble auto-hides or repositions when the soft keyboard is open (`:overlay`)
- [ ] Peek state: bubble tucks 28 dp under the snapped edge after N idle minutes (`:overlay`)

### Character / rendering
- [ ] Rive artboard `champi_mushroom` integration via `rive-android` (`:character`)
- [ ] `AppState` to state-machine input bridge: maps `state`, `level`, `attention`, `mood` inputs (`:character`)
- [ ] All seven `state` enum values wired: `idle`, `listening`, `thinking`, `speaking`, `notifying`, `error`, `sleeping` — idle animation active with breathing/blink/glance micro-motion (`:character`)
- [ ] `attention` input driven by finger position during drag (`:character`)
- [ ] Collapsed bubble (56 dp) and expanded avatar (96 dp) rendered from one artboard at two scales (`:character`)
- [ ] Interruptible transitions: new state never waits for current animation to finish (`:character`)

### Infrastructure
- [ ] `AppState` StateFlow definition in `:core` — character state, conversation placeholder, audio placeholder
- [ ] DataStore setup for bubble position and peek timing (`:core`)

## Done Definition
- Bubble renders the Rive mushroom with idle animation (breathing, blinking) over other apps
- Dragging the bubble moves it; releasing snaps it to the nearest screen edge
- Dragging to the bottom-center dismiss zone hides the bubble
- Single-tapping the bubble opens an empty bottom-sheet panel; tapping outside collapses it
- Long-pressing shows quick-action targets (visual only, actions are stubs)
- The expanded panel header shows the 96 dp avatar rendered from the same artboard
- After N idle minutes the bubble tucks under the edge (peek state)
- Manually setting character state to each of the 7 values (via debug toggle or ADB) shows distinct visual treatment

## Parallel work
- `:character` Rive integration and `:overlay` touch/panel work can proceed in parallel once the `AppState` contract in `:core` is defined
- Rive asset creation (art) is independent of code

## Phase dependencies
- Requires: Phase 0 (foreground service, overlay window, DI graph, module scaffold)

## Complexity
- Overlay/UI: L
- Character: L
- Assistant/Providers: none
- Infra: S

## Risks
- Rive artboard with 7 states and 4 inputs needs to be authored or sourced — art pipeline could bottleneck
- Touch handling on `WindowManager` overlays is tricky: distinguishing tap vs drag vs long-press without consuming touches meant for underlying apps
- `ComposeView` inside an overlay has known issues with recomposition lifecycle on some Android versions
