# Repository Guidelines

## Project Structure & Module Organization
This Kotlin Multiplatform library exposes the `core/` module declared in `settings.gradle.kts`. Shared source lives in `core/src/commonMain/kotlin`, while platform-specific implementations belong under `core/src/<platform>Main/kotlin` when they are added. Put portable tests in `core/src/commonTest/kotlin`; introduce platform-specific suites only when behavior diverges. The `gradle/` directory holds the version catalog (`libs.versions.toml`), and the root `build.gradle.kts` is limited to plugin aliases. A legacy `library/` folder from the upstream template is not wired into the build—leave it untouched unless you re-enable it in `settings.gradle.kts`.

## Build, Test, and Development Commands
Run `./gradlew build` to compile every target and execute default checks. Use `./gradlew :core:check` for quicker feedback during feature work, and `./gradlew :core:jvmTest` to focus on the JVM suite. Generate a local Maven artifact for integration testing with `./gradlew :core:publishToMavenLocal`, and clean artifacts with `./gradlew clean` before validating release builds.

## Coding Style & Naming Conventions
Follow the official Kotlin style: four-space indentation, trailing commas in multiline collections, and descriptive identifiers. Keep file names aligned with the primary type they declare, as in `core/src/commonMain/kotlin/model/Event.kt`. Use `UpperCamelCase` for classes and interfaces, `lowerCamelCase` for functions and properties, and `ALL_CAPS` for compile-time constants. Document any non-obvious decisions inline with concise comments.

## Testing Guidelines
Write unit tests with `kotlin.test` beside the code they exercise, naming files `<Feature>Test`. Each new public API should ship with at least one happy-path and one edge-case assertion. Prefer the common test source set; add platform-specific suites only when necessary. Run `./gradlew :core:check` (or the narrower `./gradlew :core:jvmTest`) before submitting changes.

## Commit & Pull Request Guidelines
Compose commit subjects in the imperative mood (for example, `Add event signature validation`) and keep commits scoped to a single concern. Pull requests should summarize intent, highlight risk areas, link related issues, and list verification steps (command output or screenshots). Request early review for API changes or build tooling updates.

## Publishing & Configuration Tips
Manage signing keys and Maven Central credentials outside the repository—use local `gradle.properties` or CI secrets, never commits. Update `group` and `version` in `core/build.gradle.kts` alongside release branches, and document any publishing workflow changes in the PR description so downstream consumers can track them.
