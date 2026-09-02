# Phase 5: Notifications + first device actions

## Goal
Champi can raise proactive notifications (with system tray mirroring and reply actions) and execute the first set of device actions: alarms, timers, and calendar events.

## Spec references
N1, N2, N3, P6 (notification action open), C2 (notifying state)

## Deliverables

### Overlay / UI surface
- [ ] Notification action: tapping the notification opens the conversation panel (`:overlay`)
- [ ] Action cards in message list: collapsed inline cards for running timers with undo affordance (`:overlay`)
- [ ] Confirmation dialog for destructive actions (`:overlay`)

### Character / rendering
- [ ] `notifying` state activation: bubble pulses when a proactive notification is raised (`:character`)

### Assistant / orchestration
- [ ] Proactive notification engine: assistant layer raises notifications, classified as silent or urgent (`:assistant`)
- [ ] Client-side rate limit on proactive interrupts, user-tunable (`:assistant`)
- [ ] Tool-call flow: LLM emits `toolCall` events, orchestrator routes to `ActionProvider`, returns `ToolResult` (`:assistant`)

### Providers
- [ ] `ActionProvider` implementation for alarms and timers (`:actions`)
- [ ] `ActionProvider` implementation for calendar events (`:actions`)
- [ ] Per-action permission gating and toggle (`:actions`)
- [ ] `ToolSpec` definitions for alarm/timer/calendar actions (`:actions`)

### Infrastructure
- [ ] System tray notification with reply actions (works when overlay is hidden or phone is locked) (`:app`)
- [ ] `SCHEDULE_EXACT_ALARM` permission request on first alarm action (`:app`)
- [ ] Quick Settings tile to open the panel (`:app`)
- [ ] DataStore for per-action toggles and rate-limit settings (`:core`)

## Done Definition
- The assistant can proactively raise a notification; the bubble pulses and the system tray shows the notification with a reply action
- Tapping the notification opens the conversation panel
- Asking champi to set a timer creates a system alarm; the panel shows an inline timer card with undo
- Asking champi to create a calendar event opens a pre-filled calendar intent or inserts directly (with confirmation)
- Rate limiting is observable: after N proactive notifications in a window, further ones are suppressed
- Actions respect per-action toggles in settings (disabled actions are refused gracefully)

## Parallel work
- Notification system (`:app` + `:assistant`) can be built in parallel with action providers (`:actions`)
- Quick Settings tile is independent of both

## Phase dependencies
- Requires: Phase 2 (turn orchestration with tool-call flow, provider interfaces including `ActionProvider` and `ToolSpec`)
- Requires: Phase 4 (routing policy, so tool calls route correctly)

## Complexity
- Overlay/UI: M
- Character: S
- Assistant/Providers: L
- Infra: M

## Risks
- `SCHEDULE_EXACT_ALARM` restricted on Android 12+; some OEMs further restrict it
- Proactive notification timing: too aggressive annoys users, too conservative makes the feature invisible
- Calendar provider access varies across devices and accounts; some require additional permissions
