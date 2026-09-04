# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- `ActionSettingsRepository` (`:core`, issue #38, partial — see below) — DataStore-backed `ALARM_ACTIONS_ENABLED`/`CALENDAR_ACTIONS_ENABLED`/`PROACTIVE_RATE_LIMIT_PER_HOUR` (default: both enabled, 3/hr), now wired into `AlarmTimerActionProvider` and `CalendarActionProvider` so disabling either returns a graceful `ToolResult` error instead of scheduling/inserting — this closes the "per-action toggle" acceptance criterion left open on #41 and #42
- Quick Settings tile (`ChampiTileService` in `:app`, issue #38, partial): starts `ChampiService` and requests the conversation panel open via a new `AppStateHolder.requestOpenPanel()` nonce (a `StateFlow` rather than a bare event, so a tap racing service startup still lands instead of being lost). Verified end-to-end on real hardware: `adb shell cmd statusbar add-tile`/`click-tile`, confirmed via `dumpsys activity services` (service started with reason "tile onclick") and a screenshot showing the expanded panel actually on screen. The system-tray proactive notification with `RemoteInput` reply this issue also asks for is deferred — its "reply submitted as a new turn" criterion needs `TurnOrchestrator` (#20) and the rate-limiter (#39), neither built yet — so #38 stays open for that piece
- `CalendarActionProvider` (`:actions`, issue #42, partial — see below) — real `create_event` `ActionProvider`: without `READ_CALENDAR`/`WRITE_CALENDAR`, opens a pre-filled `ACTION_INSERT` calendar-app intent for the user to review and save; with the permission granted, inserts directly into `CalendarContract.Events` and returns `{"status":"created","eventId":<id>}`. Verified on real hardware against the device's actual synced Google calendar account (a real event created and confirmed via `content query`, then deleted by the test itself to avoid leaving data behind). Still blocked on #38 (per-action DataStore toggle) and #40 (the destructive-confirmation dialog this issue explicitly wants in front of every direct insert), so issue #42 stays open for those two acceptance criteria
- `AlarmTimerActionProvider` (`:actions`, issue #41, partial — see below) — real `set_alarm`/`set_timer` `ActionProvider` scheduling via `AlarmManager.setExactAndAllowWhileIdle`, gated on `canScheduleExactAlarms()` on API 31+ with a clear `ToolResult` error when denied instead of silently failing; `AlarmReceiver` posts the firing notification. Verified on real hardware: an instrumented test suite confirms both the success path (a real `RTC_WAKEUP` alarm visible in `dumpsys alarm`, routed to `AlarmReceiver`) and the permission-denied error path, branching on the device's actual exact-alarm grant state rather than toggling it mid-test — mutating that grant against the test's own live process kills it (signal 9), the same failure mode `:audio`'s instrumented tests hit revoking `RECORD_AUDIO`. Still blocked on #38 (per-action DataStore toggle) and #40 (message-list undo card), so issue #41 stays open for those two acceptance criteria
- Spring animation on bubble edge-snap release (issue #10), instead of an instant jump
- `SettingsActivity` (`:app`) — wires the quick-actions Settings target to a real screen: quick-actions geometry (radial arc / edge rail) and peek idle timeout (0-15 min, 0 disables peek), both backed by `OverlayPreferencesRepository`
- Partial TalkBack accessibility (issue #58, remainder blocked on #31/#44 which don't exist yet): bubble now has `contentDescription` plus semantic click/long-click actions mirroring its custom gesture detector, which was otherwise invisible to TalkBack; expanded panel gained a real 48dp "Close panel" button (replacing a 32x4dp non-focusable decorative bar); Settings screen's peek-timeout slider gained a `contentDescription`
- Provider API interface definitions (issue #17) in `:providers:api` — `Provider`/`Locality`/`Cost`/`ProviderCapabilities`, `WakeWordProvider`, `VadProvider`, `SttProvider`, `LlmProvider`, `TtsProvider`, `ActionProvider`, plus their model types (`PcmFrame`, `Transcript`, `Conversation`, `LlmEvent`, `AudioChunk`, `ToolSpec`/`ToolCall`/`ToolResult`) — pure Kotlin, no Android dependencies, unblocking the M2-M5 provider/routing work
- `AudioCapture`/`PlaybackQueue` (`:audio`, issue #23) — shared 16kHz PCM capture/playback wrapping `AudioRecord`/`AudioTrack` that wake word, VAD, STT, and TTS will all depend on; ref-counted so concurrent collectors of `AudioCapture.pcmFlow()` never open a second `AudioRecord` on the same input
- Room database (`:core`, issue #16) — `ConversationEntity`/`MessageEntity`/`MessageDao`, `AppDatabase` wired into Hilt, and `ModelFileStore` for versioned on-disk model paths; schema versioned at 2 from the start specifically to exercise a real migration (`MIGRATION_1_2`), verified with an instrumented `MigrationTestHelper` test against the checked-in schema JSON, all passing on real hardware

### Fixed
- `AudioCapture` only checked `checkSelfPermission` before starting capture — confirmed on real hardware that `AudioPolicyService` can still refuse to actually start the input even when that check passes, leaving the flow silently hung (zero emissions, no error) instead of surfacing anything; now also checks `recordingState` right after `startRecording()` and closes the flow with a `SecurityException` if it didn't actually start. Verified with a new on-device instrumented test suite (`audio/src/androidTest`) — real `AudioRecord` frame emission, the no-second-instance guarantee under concurrent collection, and cancellation stopping emission within ~300ms, all passing on a physical device
- Launching `SettingsActivity` via an implicit intent (custom action + manifest intent-filter) threw `ActivityNotFoundException` despite the filter being correctly registered — apps targeting API 31+ can't resolve implicit intents to a non-exported activity, even within the same app; switched to an explicit intent via `Intent.setClassName`
- The repo's CodeQL "Analyze (java-kotlin)" check had been silently failing on every PR since at least #67 (not a required check, easy to miss) — its default `autobuild` setup couldn't trigger the Kotlin extractor across this multi-module Gradle build; replaced with an explicit workflow that builds the same way `android-ci.yml` does
- `MainActivity` required both overlay and notification permission before starting `ChampiService`, contradicting the spec: denying `POST_NOTIFICATIONS` should omit the visible notification, not block the service from running at all; gated on overlay permission alone
- Expanded panel's collapse-on-tap gesture wrapped the entire content `Column` in `.clickable`, which merges all non-interactive descendant semantics into one TalkBack node — harmless today but a trap for issue #21's future input row; moved onto a dedicated background layer instead
- Expanded panel window never cleared `FLAG_NOT_FOCUSABLE`, so a future text-input row (issue #21) would never be able to receive keyboard focus; `WindowSpec` now carries a `focusable` bit, set for the expanded panel only
- Peek idle timeout was a hardcoded 3-minute constant; now DataStore-backed (`peekMinutes`, default 5, 0 disables peek) and resets on any non-`IDLE` character state, not just gestures (issue #11)
- `AppState` was missing the `attention`/`mood` fields the state-machine spec calls for (issue #9); added with setters on `AppStateHolder` — wiring `attention` to live finger position during quick-actions is left for a follow-up, since it needs the gesture detection restructured from discrete click targets to continuous drag hit-testing
- The initial spring-animation attempt crashed the process: `Animatable.animateTo` needs a `MonotonicFrameClock`, only available on a coroutine scope tied to the Composition, not the plain `CoroutineScope` passed down from `OverlayManager`; now uses a `rememberCoroutineScope()`-backed scope for the animation specifically

### Added
- Phase 1 character + interaction: single `WindowManager` overlay window that resizes/repositions per state (collapsed bubble / long-press quick-actions / tap-to-expand panel) — the standard "chat heads" technique, since fullscreen touch-passthrough would require the `@hide` `OnComputeInternalInsetsListener` API
- Drag-to-move with edge-snap on release, and a bottom-center dismiss zone that hides the bubble until the app is relaunched (`OverlayRoot`, `:overlay`)
- Tap-to-expand bottom panel (60% screen height) with a placeholder "no conversation yet" state; collapses on background tap or swipe-down (`ExpandedPanel`, `:overlay`)
- Long-press quick-actions surface with both geometries from the open design question implemented (`QuickActionsLayer`, `:overlay`): radial arc (default) and edge rail, selectable via a persisted `OverlayPreferencesRepository` preference once a settings UI exists
- `CharacterPlaceholder` (`:overlay`) — animated stand-in for the Rive `champi_mushroom` artboard; color/pulse speed vary across all 7 `CharacterState` values
- `AppState`/`AppStateHolder`/`CharacterState` (`:core`) — shared app state `StateFlow`
- `OverlayPreferencesRepository` (`:core`) — DataStore-backed bubble position and quick-actions geometry persistence
- Best-effort IME-visibility detection (bubble hides while another app's keyboard is open) via display-frame comparison, since the overlay window is non-focusable and can't receive real `WindowInsets` for it
- Switched to the Compose BOM (`2026.08.00`) instead of hand-pinning individual `compose-ui`/`compose-material3` versions, after hitting a real cross-artifact version-skew bug; bumped `compileSdk`/`targetSdk` to 37 (required by Compose 1.12.0)
- Quick-actions targets now render Material Design icons (`androidx.compose.material.icons`) instead of two-letter text abbreviations, in both the radial-arc and edge-rail geometries

### Fixed
- `ChampiService.onStartCommand` now also calls `overlayManager.show()` (previously only `onCreate` did), so relaunching the app after dismissing the bubble actually brings it back
- Overlay dismiss crashed the process: `onDismiss` was calling `hide()` (which removes the view) synchronously from inside that same view's still-executing touch-gesture callback; now deferred via `view.post(...)`
- Radial quick-actions arc used raw dp magnitudes as pixel offsets in `Modifier.offset { IntOffset(...) }`, rendering the arc ~2.5x smaller than intended and putting targets outside their visible tap area
- Quick-actions window (`QUICK_ACTIONS_WINDOW_DP`) was 220dp, too small for the 96dp-radius arc of 48dp buttons (needs ~240dp min), so targets overflowed the window and got clipped at the physical screen edge when the bubble was snapped near a corner; bumped to 280dp and confirmed on-device at the worst-case top-left-corner position
- Screen bounds used for clamping (expanded panel height, quick-actions positioning, drag limits, dismiss zone) were computed from `configuration.screenHeightDp`, the *raw* display height — since this window's y-coordinate is relative to the status-bar-inset parent frame, that left ~120px of slack past the real usable bottom, letting content extend into the nav bar and get clipped there; now subtracts status/nav bar insets to get the actual usable height

### Added
- Phase 0 overlay skeleton: `MainActivity` (`:app`) requests `SYSTEM_ALERT_WINDOW` and `POST_NOTIFICATIONS` (Android 13+) via Compose, then starts `ChampiService`
- `ChampiService` (`:app`) — `specialUse` foreground service with a persistent notification, shows/hides the overlay via `OverlayManager`
- `OverlayManager` + `OverlayLifecycleOwner` (`:overlay`) — `WindowManager`-attached `ComposeView` with a manually-wired `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner`, rendering a static 56dp placeholder bubble
- `BootReceiver` (`:app`) restarts `ChampiService` after reboot if the overlay permission is still granted
- `champi.android.compose` convention plugin wires Jetpack Compose (Compose BOM) into `:app` and `:overlay`
- GitHub Actions workflow (`android-ci.yml`): runs `./gradlew lint` and `./gradlew assembleDebug` on every PR to `main`

### Added
- Gradle multi-module scaffold with 11 modules: `:app`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`, `:providers:remote`, `:audio`, `:actions`, `:context`, `:core`
- Version catalog (`gradle/libs.versions.toml`) for centralized dependency version management
- Convention plugins (`build-logic`) for shared Android configuration: `minSdk 29`, `targetSdk 35`, `compileSdk 35`, Kotlin 1.9.24
- Gradle wrapper (Gradle 8.7) and root project settings
- Each module includes a minimal `AndroidManifest.xml` and package stub
- Hilt/Dagger DI graph wired across all modules (Hilt 2.51.1, KSP 1.9.24-1.0.20)
  - `ChampiApplication` annotated with `@HiltAndroidApp`
  - `champi.android.hilt` convention plugin applies KSP + Hilt to any module
  - Placeholder `@Module @InstallIn(SingletonComponent::class)` in all 11 modules: `:core`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`, `:providers:remote`, `:audio`, `:actions`, `:context`
  - `Logger` class in `:core` annotated with `@Inject` as a smoke-test injectable
  - `HiltSmokeTest` instrumented test in `:app` verifies the graph resolves `Logger` from `:core`
  - `hilt-android-testing` wired for test components via `HiltTestRunner`

### Fixed
- `champi.android.hilt` plugin was missing from `:providers:remote`, `:audio`, `:actions`, `:context`, contradicting the "wired across all modules" claim; applied it and added matching placeholder DI modules
- Pinned a JVM 17 Gradle toolchain in `build-logic/convention` and the Android convention plugins — without `org.gradle.java.home`, `kotlin-dsl` inferred the Kotlin compiler target from whichever local JDK happened to launch the Gradle daemon, breaking the build on machines with multiple JDKs installed
- Build now runs under JDK 26: bumped Gradle wrapper to 9.7.1, AGP to 9.4.0, Kotlin to 2.3.0, KSP to 2.3.11, Hilt to 2.60.1 (the only combination where KSP2's `KspAATask` and Hilt's Gradle plugin agree). AGP 9's built-in Kotlin support replaces the standalone `org.jetbrains.kotlin.android` plugin, so the Android convention plugins now configure `com.android.build.api.dsl.{Application,Library}Extension` directly instead of applying it and using `KotlinAndroidProjectExtension`. Android module bytecode (`compileOptions`) still targets Java 17, only the Gradle/toolchain JVM moved to 26. Raised `org.gradle.jvmargs` heap/metaspace (2g/512m → 4g/1g) to cover the heavier AGP 9 + Kotlin 2.3 daemon
