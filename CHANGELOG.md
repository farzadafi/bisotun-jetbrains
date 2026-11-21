# Changelog

All notable changes to this project are documented in this file.

## [1.1.0] - 2026-11-20

### Changed
- **Go extraction migrated from the bounded tokenizer to native JetBrains Go PSI.** Go flow
  extraction now uses the official Go plugin PSI (`GoFile`, `GoFunctionOrMethodDeclaration`,
  `GoTypeSpec`, `GoCallExpr`, `GoReferenceExpression`, `GoSignature`, `GoType`, ...) and the
  plugin's own reference/type resolution instead of text parsing. Nothing is matched by name alone:
  a call the Go plugin cannot resolve is reported as `unresolved` and is not expanded.
- Go extraction now covers declarations and relationships through PSI: packages and imports
  (aliases, dot and blank imports), structs, interfaces, embedded types, fields and tags, defined
  types and type aliases, generic type parameters and constraints, functions and methods with
  pointer/value receivers, parameters and multiple return values, local `var` and `:=`
  declarations with inferred types, composite literals, control flow (`if`/`else`, `for`/`range`,
  `switch`/type switch, `select`, `return`, `defer`, `go`, channel sends), anonymous functions and
  calls across same-package files and project-local imported packages.
- `GoFlowExtractor` is now loaded only when the Go plugin is present at runtime
  (`com.goide.psi.GoFile` availability check); without it the action reports the GoLand
  prerequisite with a clear message.

### Platform and build
- The build/test platform moved from IntelliJ IDEA Community to **IntelliJ IDEA Ultimate 2024.1.4**
  with the official Go plugin dependency (`org.jetbrains.plugins.go`, version `241.17011.2`). This
  is required because the Go plugin descriptor states that the only compatible IDE is IntelliJ IDEA
  Ultimate; the launched IDE for Go extraction is **GoLand**, which bundles the same plugin.
- `plugin.xml` declares `org.jetbrains.plugins.go` as an optional dependency. Existing languages
  keep working where their plugins/PSI are available.
- Plugin verifier matrix changed from `IC-*` to GoLand: `GO-2024.1.4`, `GO-2024.2`, `GO-2024.3`.
- Go tests were rewritten as platform-fixture tests against real Go PSI (including a configured
  Go module for cross-package resolution); the tokenizer unit tests were removed.

### Notes and limitations
- **Go support requires JetBrains GoLand with the bundled Go plugin enabled.** IntelliJ IDEA
  Community alone does not provide the Go PSI APIs required for semantic Go flow extraction.
- Interface calls resolve to the interface method declaration (no body); concrete implementations
  and dynamic/reflection targets are not enumerated and stay unresolved.
- Generated files, `vendor/` sources and declarations outside the project content (including
  GOROOT/library sources) are not treated as project flow steps; built-ins and standard-library
  noise are filtered.
- Go generic method bodies are not instantiated; calls through unresolved type parameters stay
  unresolved.

## [1.0.9] - 2026-11-20

### Added
- **Go** flow extraction for `.go` files, using the same bounded-tokenizer architecture as
  Python and C++ in IntelliJ IDEA Community (the JetBrains Go plugin `com.goide` is not part of
  the Community SDK this plugin targets, and no Go PSI class is referenced):
  - package clause and imports, including `alias "path"`, dot and blank imports;
  - structs, interfaces, embedded types, fields and struct tags, generics/type parameters,
    defined types and type aliases;
  - functions and methods with parameters, return types and multiple return values, pointer and
    value receivers, anonymous functions/closures;
  - local `var` declarations and short declarations (`:=`) with initializer type inference
    (`NewOrderService(...)`, `&OrderService{}`, constructor return types, field types);
  - control-flow statements: `if`/`else`, `for`/`range`, `switch` (including type switches),
    `select`, `return`, `defer`, `go`, plus `panic`/`recover` calls where they are real call sites;
  - project-local model types referenced by the visited functions, following field and embedded
    types inside the package.
- Go call resolution across the files of a package and into project-local imported packages,
  resolved through the `go.mod` module path (with `vendor/` and project-root fallbacks), plus
  method calls on the statically known type of a receiver/field/local chain.
- Go fixtures under `src/test/resources/fixtures/go` (multi-package: `service`, `notify`, `cmd`)
  and 25 regression tests covering detection, outline, depth, cross-file and cross-package calls,
  interface dispatch, models, filtering, malformed input and existing-language detection.

### Notes and limitations
- Go extraction is a tokenizer tier: it never claims PSI semantics, never fabricates a
  declaration, and the copied markdown starts with a warning that says so.
- Interface dispatch is reported at the interface method declaration (the statically declared
  target, which has no body); concrete implementations and dynamic targets are not enumerated and
  stay unresolved. Calls on values whose type cannot be inferred textually also stay unresolved.
- Generated files (`// Code generated ... DO NOT EDIT.`), `_test.go` files and `vendor/` sources are
  not used as resolution sources. Standard-library and built-in calls are filtered.
- Supported IDE matrix is unchanged (`IC-2024.1.4`, `IC-2024.2`, `IC-2024.3`); no Go plugin is
  declared as a dependency, so the plugin still loads in IDEs without Go support.

## [1.0.8] - 2026-11-20

### Fixed
- C++ flow extraction now resolves class methods across a translation unit: a call such as
  `runOne()` inside an out-of-line `Pipeline::run()` definition is bound to the later same-file
  method, and header prototypes are paired with their `.cpp`/`.cc`/`.cxx` definitions instead of
  staying unresolved.
- Receiver and template member calls (`jobs_.filter()`, `worker_->execute()`) resolve through
  the enclosing class field type, including `unique_ptr`/`shared_ptr` unwrap and template methods
  defined in a quoted project-local header such as `Repository<T>::filter`.
- Lambda predicate/comparator bodies are nested callbacks of the containing call. Their
  `return` statements and inner calls are no longer emitted as unrelated top-level execution
  steps of the caller.
- Unresolved standard-library noise (`error.what()`, container accessors, `std::` helpers) is
  filtered; project-defined methods with the same names still resolve. Quoted `#include`
  directives are treated as lookup dependencies, not executable steps of a function.
- Overloads are accepted only when arity matches uniquely. Same-named methods on unrelated
  classes or namespaces stay unresolved rather than being guessed. Include lookup is a bounded
  BFS of quoted headers plus the same-directory companion file; `ProcessCanceledException`
  still propagates.

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
