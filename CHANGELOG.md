# Changelog

All notable changes to this project are documented in this file.

## [1.0.7] - 2026-11-11

### Fixed
- Migrated `FlowExtractionDispatcher` to the non-deprecated
  `Application.runReadAction(Computable<T>)` API (via
  `ApplicationManager.getApplication().runReadAction(Computable { ... })`) for both flow
  extraction and language detection.
  - `ProcessCanceledException` is rethrown so cancellation during indexing or modal progress
    is never swallowed; genuine failures are still converted into
    `FlowExtractionOutcome.failure(...)` exactly as before.
  - `detectLanguage` keeps its fail-soft contract (unknown metadata degrades to
    `LanguageKind.UNKNOWN`).
- Renamed the optional Java descriptor `java-part.xml` to `bisotun-java.xml` and updated the
  two `config-file="bisotun-java.xml"` references in `plugin.xml`. The optional
  `com.intellij.java` / `com.intellij.modules.java` dependencies are otherwise unchanged, so
  Java-specific configuration is only loaded in IDEs that provide the Java plugin.

### Notes
- SDK baseline remains IntelliJ IDEA Community `2024.1.4`.
- The Java strategy is still isolated from non-Java IDEs at runtime: `FlowExtractionDispatcher`
  probes `com.intellij.psi.PsiMethod` with `Class.forName` before registering
  `JavaFlowExtractor`, and every Java-PSI referencing class is only reachable through that
  registration. WebStorm, PyCharm and other platform-based IDEs load the plugin without the
  Java plugin present.

## [1.0.6] - 2026-11-05

### Fixed
- Maintenance release: fully resolved all plugin verifier warnings to ensure seamless IDE
  compatibility.
- Migrated deprecated `ReadAction.compute` calls to the modern `runReadAction` standard.
- Optimized optional dependency configuration (`config-file` declaration) per JetBrains
  Platform best practices.

## [1.0.5] - 2026-11-05

### Added
- Robust multi-language flow extraction support for **Python** (`.py`, `pyw`, `pyi`) with full
  absolute/relative import resolution.
- Resolved IntelliJ IDEA module classloader constraints via optional Java dependencies,
  enabling seamless multi-IDE compatibility (IntelliJ IDEA, PyCharm, WebStorm).

## [1.0.4] - 2026-11-05

### Added
- Inline animated GIF demo inside the plugin description for direct viewing in the IntelliJ
  IDEA plugin manager.

## [1.0.3] - 2026-11-05

### Added
- Official branded plugin icon and visual assets.
- Enhanced plugin description with the historical and architectural philosophy of Mount
  Bisotun.

## [1.0.2] - 2026-11-05

### Added
- Full flow extraction support for JS, JSX, TS, and TSX files in IntelliJ IDEA Community.
- Refactored architecture into a unified dispatcher strategy for improved stability.
