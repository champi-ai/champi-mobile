# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- Gradle multi-module scaffold with 11 modules: `:app`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`, `:providers:remote`, `:audio`, `:actions`, `:context`, `:core`
- Version catalog (`gradle/libs.versions.toml`) for centralized dependency version management
- Convention plugins (`build-logic`) for shared Android configuration: `minSdk 29`, `targetSdk 35`, `compileSdk 35`, Kotlin 1.9.24
- Gradle wrapper (Gradle 8.7) and root project settings
- Each module includes a minimal `AndroidManifest.xml` and package stub
- Hilt/Dagger DI graph wired across all modules (Hilt 2.51.1, KSP 1.9.24-1.0.20)
  - `ChampiApplication` annotated with `@HiltAndroidApp`
  - `champi.android.hilt` convention plugin applies KSP + Hilt to any module
  - Placeholder `@Module @InstallIn(SingletonComponent::class)` in `:core`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`
  - `Logger` class in `:core` annotated with `@Inject` as a smoke-test injectable
  - `HiltSmokeTest` instrumented test in `:app` verifies the graph resolves `Logger` from `:core`
  - `hilt-android-testing` wired for test components via `HiltTestRunner`
