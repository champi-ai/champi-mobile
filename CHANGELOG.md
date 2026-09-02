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
  - Placeholder `@Module @InstallIn(SingletonComponent::class)` in all 11 modules: `:core`, `:overlay`, `:character`, `:assistant`, `:providers:api`, `:providers:edge`, `:providers:remote`, `:audio`, `:actions`, `:context`
  - `Logger` class in `:core` annotated with `@Inject` as a smoke-test injectable
  - `HiltSmokeTest` instrumented test in `:app` verifies the graph resolves `Logger` from `:core`
  - `hilt-android-testing` wired for test components via `HiltTestRunner`

### Fixed
- `champi.android.hilt` plugin was missing from `:providers:remote`, `:audio`, `:actions`, `:context`, contradicting the "wired across all modules" claim; applied it and added matching placeholder DI modules
- Pinned a JVM 17 Gradle toolchain in `build-logic/convention` and the Android convention plugins — without `org.gradle.java.home`, `kotlin-dsl` inferred the Kotlin compiler target from whichever local JDK happened to launch the Gradle daemon, breaking the build on machines with multiple JDKs installed
- Build now runs under JDK 26: bumped Gradle wrapper to 9.7.1, AGP to 9.4.0, Kotlin to 2.3.0, KSP to 2.3.11, Hilt to 2.60.1 (the only combination where KSP2's `KspAATask` and Hilt's Gradle plugin agree). AGP 9's built-in Kotlin support replaces the standalone `org.jetbrains.kotlin.android` plugin, so the Android convention plugins now configure `com.android.build.api.dsl.{Application,Library}Extension` directly instead of applying it and using `KotlinAndroidProjectExtension`. Android module bytecode (`compileOptions`) still targets Java 17, only the Gradle/toolchain JVM moved to 26. Raised `org.gradle.jvmargs` heap/metaspace (2g/512m → 4g/1g) to cover the heavier AGP 9 + Kotlin 2.3 daemon
