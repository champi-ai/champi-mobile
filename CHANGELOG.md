# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

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
