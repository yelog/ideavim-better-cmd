# IdeaVim Better Cmd

A JetBrains (IntelliJ Platform) plugin that replaces (or augments) the native IdeaVim Ex (`:`) command line and search (`/`) prompt with a lightweight popup, aiming for a faster and more extensible command experience.

> Status: Experimental / WIP. Expect breaking changes.

## Features (Current)
- Intercepts `:` and `/` in (mostly) Vim *Normal* mode and shows a floating popup instead of the bottom command/search panel.
- Provides a minimal UI with a text field (command or search mode).
- Attempts to invoke IdeaVim internal command & search execution through reflection (best-effort; may silently fail depending on IdeaVim version).
- Closes the original IdeaVim Ex panel if it appears (multi-pass delayed suppression).

## How It Works
1. An application-level service (`BetterCmdTypedHandlerInstaller`) installs:
   - A `TypedActionHandler` wrapper (for Insert-mode fallback / future use).
   - An `IdeEventQueue` dispatcher to capture `KeyEvent.KEY_PRESSED` for `:` and `/`.
2. When triggered, it cancels / suppresses the native panel and shows `BetterCmdLinePopup`.
3. On Enter:
   - For `:` it tries to locate a *command group* in `VimPlugin` via reflection and call an execution-like method.
   - For `/` it tries to locate a *search group* and run a search-like method / set pattern.
4. Reflection is heuristic; method discovery is signature-based and may fail silently if IdeaVim internals change.

## Limitations
- Normal mode detection is currently heuristic. If detection fails, interception may be too aggressive or too permissive.
- Reflection against IdeaVim internals is fragile and version-dependent.
- Some commands or searches may not execute if no suitable internal method is found.
- Race conditions: IdeaVim may still momentarily show its native panel (multi-shot close attempts mitigate this).
- No command history / completion UI yet.

## Roadmap (Planned)
- Proper Normal mode detection using stable IdeaVim API (if/when available).
- Command history + navigation.
- Incremental / live search preview.
- Async execution & result panel.
- Extensible command providers (3rd-party plugin API).
- Richer UI (icons, theming, inline docs).

## Installation
### From Source
```
./gradlew buildPlugin
```
Resulting ZIP: `build/distributions/ideavim-better-cmd-<version>.zip`  
Install via: Settings > Plugins > ⚙ > Install Plugin from Disk…

### Requirements
- IntelliJ Platform 2024.3.x (matching the version in `gradle.properties`).
- IdeaVim plugin (optional dependency; features degrade gracefully if absent).

## Usage
1. Ensure IdeaVim is enabled.
2. Open a file, press `Esc` to return to Normal mode.
3. Press `:` or `/`.
4. A floating popup appears:
   - Type an Ex command (e.g. `w`, `noh`, `set number`) and press Enter.
   - Or type a search pattern after `/` and press Enter.
5. Press `Esc` to cancel.

## Development
### Run in IDE Sandbox
```
./gradlew runIde
```

### Code Structure
```
src/main/kotlin/…/cmdline/
  BetterCmdLineService.kt        – Orchestrates popup + reflection execution
  BetterCmdLinePopup.kt          – Swing-based lightweight popup
  BetterCmdTypedHandlerInstaller – Installs handlers & global key dispatcher
startup/
  MyProjectActivity.kt           – Ensures early service initialization
```

### Reflection Strategy
Methods are chosen by:
- Group discovery: methods containing `<keyword>Group` (e.g. `commandGroup`, `getCommandGroup`)
- Execution discovery: name contains `runEx`, `execute`, `process`, or `search`, and last parameter is `String`

If no suitable method is found, a warning is logged.

## Logging
Enable debug logging:  
Help > Diagnostic Tools > Debug Log Settings  
Add:  
```
#com.github.yelog.ideavimbettercmd
```

## Contributing
PRs welcome. Please:
- Keep changes modular.
- Avoid hard dependencies on unstable IdeaVim internals (wrap with reflection & fail gracefully).
- Add concise docs for new commands / features.

## Safety & Compatibility
| Aspect          | Status                 |
|-----------------|------------------------|
| IdeaVim Missing | Popup still shows; execution is skipped |
| Unsupported API | Logged; no hard failure |
| Multi-project   | First open project supplies context |
| Threading       | UI ops on EDT; reflection on calling thread |

## Potential Enhancements
- PSI-aware ex commands
- Multi-buffer / tab commands
- Command palette integration
- Fuzzy match / suggestions

## Uninstall
Just remove the plugin normally. No persistent state is stored (history not implemented yet).

## License
MIT (proposed – update if different).

## Disclaimer
This plugin is not affiliated with nor endorsed by the official IdeaVim project. Use at your own risk—internal API usage may break across versions.

---
Happy hacking! Feel free to open issues with logs if command/search execution silently fails.
