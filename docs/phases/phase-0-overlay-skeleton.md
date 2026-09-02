# Phase 0: Overlay skeleton

## Goal
A foreground service starts, draws a static bubble overlay on top of other apps, and survives process death and reboot.

## Spec references
B1, B7

## Deliverables

### Overlay / UI surface
- [ ] `ChampiService` foreground service with `foregroundServiceType="microphone|specialUse"` (`:app`)
- [ ] `OverlayManager` — creates `WindowManager` overlay with `TYPE_APPLICATION_OVERLAY`, adds a static 56 dp placeholder bubble (`:overlay`)
- [ ] `ComposeView` hosting with manually provided `LifecycleOwner`, `ViewModelStoreOwner`, `SavedStateRegistryOwner` (`:overlay`)
- [ ] Persistent foreground notification so the service is not killed

### Infrastructure
- [ ] Gradle multi-module scaffold: `:app`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`, `:providers:remote`, `:audio`, `:actions`, `:context`, `:core`
- [ ] Hilt/Dagger DI graph wired across modules
- [ ] `SYSTEM_ALERT_WINDOW` permission request on first launch (basic — full onboarding flow deferred)
- [ ] `POST_NOTIFICATIONS` runtime permission request (Android 13+)
- [ ] `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_SPECIAL_USE` manifest declarations
- [ ] Boot receiver to restart the service after reboot
- [ ] CI: assembleDebug + lint on PRs
- [ ] minSdk 29, targetSdk current

## Done Definition
- App installs on a minSdk 29 device/emulator
- Granting overlay permission shows a static circular placeholder on top of the home screen and other apps
- Killing the app via recents and reopening restores the overlay
- Rebooting the device restarts the service and shows the overlay without manual launch
- The foreground notification is visible in the system tray

## Parallel work
- None: this phase is the foundation all other work depends on

## Phase dependencies
- Requires: none

## Complexity
- Overlay/UI: M
- Character: none
- Assistant/Providers: none
- Infra: L

## Risks
- OEM-specific battery optimization may kill the foreground service despite correct declarations (Xiaomi, Samsung, Huawei autostart restrictions)
- `SYSTEM_ALERT_WINDOW` grant flow varies across OEMs; some redirect to an app list rather than a toggle
