# AGENTS.md — Bisotun Flow Extractor (JetBrains Plugin)
This document is **authoritative** for any coding agent working on this repository.
If any instruction conflicts with chat messages or assumptions, **this file wins**.

## 0) Critical rules (must-follow)
1. **Do not break Java flow extraction.** Java path is production-grade and must remain reference-based (`PsiMethod` + resolve + `DefinitionsScopedSearch`).
2. **Never mix language logic.** Java/Kotlin/JS/TS/JSX/TSX must be isolated behind clear interfaces.
3. **No regex-based JS/TS call extraction.** The current `extractJsCallExpressions()` approach is forbidden.
4. **No synthetic/lying PSI elements.** Do not create fake `PsiElement` like `"Type: Foo"`. If something is unresolved, mark it explicitly as unresolved in the domain model.
5. **Clean code + SOLID.** Small classes, single responsibility, dependency inversion, testable pure logic where possible.
6. **No “drive-by refactors”.** Only refactor what the task requires. Prefer small, reviewable commits.
7. **Build must work with IntelliJ IDEA Community target.** JS plugin must be optional at runtime; do not hard-require it in Gradle for IC builds.

## 1) Repository goal
Extract and copy a call flow graph from the caret/root function with 4 actions:
- Deep Flow (maxDepth=4, includeModels=true)
- Shallow Flow (maxDepth=1, includeModels=true)
- Shallow Flow (no models) (maxDepth=1, includeModels=false)
- Method Only (maxDepth=0, includeModels=false; or equivalent behavior)

## 2) Current architecture (do not regress)
### Java (MUST keep)
- Java resolution uses PSI references and resolves methods/constructors.
- Java traversal uses `JavaRecursiveElementWalkingVisitor` on `method.body`.
- Dispatch for interface methods uses `DefinitionsScopedSearch.search(resolved)`.
- Must continue to filter library/out-of-content files via `ProjectFileIndex`.

### JS/TS/React (MUST be redesigned)
Current JS/TS behavior is unreliable because:
- JS PSI classes are not on the compile classpath for IC builds.
- Current fallback uses regex call matching and creates synthetic type elements.
This must be replaced with a **tiered resolver** described in section 4.

## 3) Coding standards
- Kotlin: follow JetBrains Kotlin style, prefer `when`, data classes for domain.
- No global mutable state.
- Separate “domain model” from “platform adapters”:
  - Domain: language-agnostic nodes, edges, model references, resolution states.
  - Platform adapters: IntelliJ PSI integration, indexing, file lookups, UI.
- Prefer explicit types; avoid clever reflection.
- Logging: use IntelliJ logger (`com.intellij.openapi.diagnostic.Logger`) not `println`.

## 4) Mandatory tiered JS/TS strategy
JS/TS support must work in two tiers:

### Tier A — JS plugin available at runtime (preferred)
- Detect availability at runtime:
  - `Language.findLanguageByID("JavaScript") != null` OR
  - `PluginManagerCore.isPluginInstalled(PluginId.getId("JavaScript"))`
- If available, do PSI-driven traversal:
  - Walk AST with safe checks (elementType / language id).
  - Resolve calls via `element.reference` / `multiResolve(true)` where applicable.
  - Extract functions/components without regex, using PSI structure.

### Tier B — JS plugin NOT available (IC-only)
- Provide a bounded, honest fallback:
  - A tokenizer / lightweight parser (NOT regex) over file text
  - Track bracket/paren nesting, strings, comments
  - Recognize imports/exports and function declarations/arrow assignments
  - Extract call sites conservatively
  - All unresolved references must be marked `UNRESOLVED`
- Never claim types; never fabricate nodes.
- Depth must still be respected (stop expanding beyond `maxDepth`).

## 5) File structure (where to put code)
Create (or refactor into) packages like:

- `com.bisotun.domain.*`
  - `FlowResult`, `FlowNode`, `FlowEdge`, `SymbolId`, `ResolutionStatus`
- `com.bisotun.language.*`
  - `LanguageKind` (JAVA, KOTLIN, JS, TS, JSX, TSX, UNKNOWN)
  - `LanguageFlowExtractor` interface (one strategy per language, `isApplicable` + `extract`)
  - `FlowExtractionDispatcher` (picks exactly one extractor per caret, never merges languages)
  - `JavaFlowExtractor` (adapter over existing Java FlowCrawler / ModelExtractor)
  - `JsTsFlowExtractor` (both tiers live here: PSI path when the JavaScript plugin is
    available, tokenizer path when it is not)
  - `PsiFlowNodes` / `LanguageKindDetector` / `LanguageKind` (shared platform helpers)
- `com.bisotun.render.*`
  - `MarkdownRenderer` (single entry point); today `com.bisotun.MarkdownFormatter`
- `com.bisotun.actions.*`
  - Keep 4 actions but extract shared logic into one reusable runner/service
    (today `com.bisotun.ExtractFlowActionBase`).

## 6) Required behavior guarantees
- Java output must remain at least as good as current.
- JS/TS/React:
  - Must not crash on files it cannot understand.
  - Must show a warning line in output when running in Tier B.
  - Must not output fake “models”; only output found exports/types if truly parsed.
- Output must include:
  - root function signature
  - execution flow list
  - (optional) models section, only when `includeModels=true` and models are real

## 7) Before you code
You must provide in the PR description:
- Which tier(s) you implemented/changed
- What files changed and why
- How you ensured Java did not regress
- Any limitations (explicit)

## 8) Forbidden actions
- Adding `bundledPlugin("JavaScript")` unconditionally for IC target
- Regex call extraction for JS/TS
- Creating synthetic PsiElements to “pretend” types exist
- Mixing Java PSI logic into JS logic (or vice versa)
