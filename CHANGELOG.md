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
