# Repository Guidelines

## Project Structure & Module Organization
- Plugin sources live in `src/main/kotlin`, with command-line logic under `cmdline/` and startup wiring under `startup/`.
- UI resources and plugin descriptors are in `src/main/resources`, notably `META-INF/plugin.xml` and `messages/`.
- Test scaffolding resides in `src/test/kotlin` (currently minimal) and `src/test/testData` for future fixtures.
- Gradle build outputs generate under `build/`; keep generated ZIPs out of version control.

## Build, Test, and Development Commands
- `./gradlew buildPlugin` – compiles, instruments, and packages the plugin ZIP to `build/distributions/`.
- `./gradlew test` – runs Kotlin test sources with the IntelliJ test framework classpath.
- `./gradlew runIde` – launches an IntelliJ sandbox with IdeaVim 2.19.0 to manually validate the popup behavior.
- Add `--info` or `--scan` only when investigating build failures.

## Coding Style & Naming Conventions
- Kotlin sources default to 4-space indentation, `UpperCamelCase` for classes, and `lowerCamelCase` for functions/fields.
- Keep popup and interceptor code under `cmdline/`; startup wiring under `startup/`; avoid scattering reflection helpers.
- Prefer extension and service classes marked with `@Service` when wiring IntelliJ components; limit singletons to lightweight helpers like `InterceptBypass`.
- When adding resources, align keys with the existing `MyBundle` namespace (e.g., `cmdline.popup.title`).

## Testing Guidelines
- Use JUnit 5 (bundled via `libs.junit`) for unit or behavioral tests; place IntelliJ fixture tests under `src/test/kotlin` mirroring package paths.
- Name tests with the `*Test` suffix and describe scenarios (e.g., `BetterCmdLineServiceTest`).
- Run `./gradlew test` before pushing; for UI flows, document manual steps executed in the PR description.

## Commit & Pull Request Guidelines
- Follow concise, imperative commit messages (`Add incremental search debounce`). Group unrelated fixes into separate commits.
- Reference GitHub issues with `Fixes #NN` when applicable and summarize changes plus verification steps in PR descriptions.
- Include screenshots or short screen recordings when UI behavior (popups, suppression timing) changes.
- Confirm the plugin builds (`./gradlew buildPlugin`) and tests pass locally before requesting review.

## Security & Configuration Tips
- Reflection calls into IdeaVim internals are version-sensitive; guard new hooks with try/catch and document fallback behavior.
- Avoid hard-coding platform versions—update `gradle.properties` instead of inlining version strings in code.
