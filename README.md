# Bisotun Flow Extractor - JetBrains Plugin

## Supported Languages

| Language | Support |
|---|---|
| **Java** | Full support with extraction chain: controller -> service interface -> implementation -> repository -> method-reference mapper -> DTO record / projection. Reference based (`PsiMethod` + resolve + `DefinitionsScopedSearch`). |
| **JavaScript / TypeScript / JSX / TSX** | Tiered support, decided at runtime (see below). Calls that cannot be resolved are reported as `unresolved` instead of being guessed. |
| **Go** | Native Go PSI support for `.go` files (**requires GoLand with the bundled Go plugin**): packages/imports (aliases, dot and blank imports), structs, interfaces, embedded types, fields and tags, defined types and aliases, generics and constraints, functions/methods with pointer and value receivers, multiple return values, local and short declarations, composite literals, control flow, `defer`/`go`/`panic`/`recover`, closures, and calls resolved through JetBrains Go references across files and imported project packages. Interface calls stay at the interface declaration; unresolved/dynamic targets stay `unresolved`. |
| **Kotlin** | Not supported (Kotlin PSI is not bundled with this plugin); the action reports that instead of producing an empty result. |

JS/TS extraction is one `LanguageFlowExtractor` (`JsTsFlowExtractor`) with two runtime paths:

- **Tier A (JavaScript plugin present)** - traversal over real PSI: references are resolved with
  `PsiReference` / `multiResolve(true)`, class components are expanded through their `render`
  method, and models are the type declarations the crawled functions really reference.
- **Tier B (JavaScript plugin absent, e.g. IntelliJ IDEA Community)** - a bounded, regex-free
  tokenizer (aware of strings, comments and bracket nesting) builds a file outline and a
  conservative call graph: same-file declarations, `./`/`../` imports, namespace imports and
  instance member calls (`this.paymentService.processPayment()`) resolved against the imported
  class that declares the called method. Everything else stays `unresolved`. The output always
  starts with a Tier B warning line.

### Go prerequisite

> **Go support requires JetBrains GoLand with the bundled Go plugin enabled.** IntelliJ IDEA
> Community alone does not provide the Go PSI APIs required for semantic Go flow extraction.

Go extraction is one `LanguageFlowExtractor` (`GoFlowExtractor`) built on the official JetBrains Go
plugin PSI (plugin id `org.jetbrains.plugins.go`, the plugin GoLand bundles):

- Declarations come from `GoFile`, `GoFunctionOrMethodDeclaration` and `GoTypeSpec`; signatures,
  receivers, fields, tags, type parameters and constraints come from the Go PSI model.
- Call sites are `GoCallExpr` references resolved by the Go plugin itself
  (`GoReferenceExpression.resolve()`), which follows receivers, fields, constructor return types,
  local declarations, same-package sibling files and project-local imported packages. Calls are
  never matched by name alone.
- Interface calls resolve to the interface method declaration (no body); concrete implementations
  and dynamic targets are not enumerated. Anything the Go plugin cannot resolve is reported as
  `unresolved` and is not expanded.
- Built-ins, generated files (`// Code generated ... DO NOT EDIT.`), `vendor/` sources and
  declarations outside the project content (including GOROOT/library sources) are filtered.
- The extractor is only registered when the Go plugin is present at runtime. Without GoLand (or
  IntelliJ IDEA Ultimate with the Go plugin), the action reports the GoLand prerequisite instead of
  falling back to a tokenizer.

## Compatible IDE Products

- **GoLand 2024.1.4 / 2024.2 / 2024.3** - Required for Go extraction; the bundled Go plugin provides
  the Go PSI. Other languages keep their existing behaviour.
- **IntelliJ IDEA Ultimate 2024.1.4** - Tier A for JS/TS (JavaScript plugin is bundled); Go works when
  the Go plugin is installed.
- **IntelliJ IDEA Community 2024.1.4** - Java support guaranteed; JS/TS works through Tier B, the
  JavaScript plugin is **not** required. Go extraction is **not available** (no Go PSI).
- **Other JetBrains products** (PyCharm, WebStorm, PhpStorm, Rider) - May have varying levels of
  support depending on bundled language plugins; Go requires the Go plugin.

## Actions

| Action | Description | Default Shortcut |
|---|---|---|
| `Copy Deep Flow` (`CopyDeepFlowAction`) | Deep flow, `maxDepth=4`, with models | `Ctrl` + `Alt` + `Shift` + `D` |
| `Copy Shallow Flow` (`CopyShallowFlowAction`) | Shallow flow, `maxDepth=1`, with models | `Ctrl` + `Alt` + `Shift` + `S` |
| `Copy Shallow Flow (No Models)` (`CopyShallowFlowWithoutModelAction`) | Shallow flow, `maxDepth=1`, no models | `Ctrl` + `Alt` + `Shift` + `M` |
| `Copy Method Only` (`CopyMethodOnlyAction`) | Only the method/function at the caret, `maxDepth=0`, no models | `Ctrl` + `Alt` + `Shift` + `C` |

All four share one implementation (`ExtractFlowActionBase` -> `FlowExtractionDispatcher`); a concrete
action only declares `maxDepth`, `includeModels` and its dialog title. `ExtractFlowAction` is kept as
another configuration (`maxDepth=4`, models) but is not registered in `plugin.xml`.

## Settings

Depth and model flags are per action (`maxDepth` / `includeModels` constructor parameters).

| Setting | Default | Description |
|---|---|---|
| `maxDepth` | `4` | Maximum traversal depth for call flow extraction (`0` = root only). |
| `includeModels` | `true` | Whether the models section is extracted and rendered. |

## Extraction Behavior

- **Root depth**: 0 (the method/function where the caret is positioned)
- **Depth boundary**: traversal stops beyond `maxDepth`
- **Cycle protection**: visited node ids prevent infinite recursion
- **Workspace boundaries**: only project sources are analyzed; library/out-of-content files are
  filtered with `ProjectFileIndex` (Java) or by only following project files (JS/TS)
- **No regex, no synthetic PSI**: JS/TS call sites come from a tokenizer (Tier B) or real PSI
  references (Tier A); nothing is fabricated to look like a declaration
- **Unresolved calls**: kept in the output and marked
  `Resolution: unresolved (no source declaration found, not expanded)`

## Output Format

The extracted flow is rendered as Markdown and copied to the clipboard:

````
# Flow Extracted by Bisotun

**Configuration:** maxDepth=4, includeModels=true

> JS/TS fallback mode (no JavaScript plugin): ...

## Execution Flow Summary

1. `OrderController#create(OrderRequest)`
2. `OrderService#create(OrderRequest)`

## Source Code & Implementations

### 1. `OrderController#create(OrderRequest)`
- File: `src/main/java/com/example/OrderController.java`
- Lines: 24-38

```java
...source...
```

## Related Data Models & Entities

### 1. `com.example.OrderRequest`
- File: `src/main/java/com/example/OrderRequest.java`
- Lines: 3-9

```java
...source...
```
````

- **Configuration line**: always rendered, so the depth/model scope of a copied flow is explicit
- **Warnings**: rendered as blockquotes (Tier B always announces itself)
- **No duplicates**: nodes are de-duplicated by their stable id (`path:startOffset-endOffset`)

## Known Limitations

1. **Tier B resolution is conservative** - same-file declarations, relative imports, namespace
   imports and instance calls whose target method exists in an imported class are expanded; a
   qualifier that matches several imported files, or no imported declaration at all, stays
   unresolved on purpose.
2. **Path aliases** (`@/...`, `tsconfig` paths) are not resolved in Tier B.
3. **CommonJS `require()`** - not resolved in Tier B; native ESM imports are used.
4. **Regex literals in JS code** (`/.../`) are tokenized as plain operators, so an unusual literal can
   hide a call site from Tier B; Tier A is unaffected.
5. **JSX/TSX component tags** are not call sites, so `<Foo />` does not create a flow edge; functions
   and hooks called inside the component body are expanded normally.
6. **Kotlin** - no Kotlin PSI, so Kotlin files are reported as unsupported.
7. **Go requires GoLand / the Go plugin** - without the JetBrains Go plugin there is no Go PSI, so
   Go flow extraction is unavailable and the action says so. GoLand must have its bundled Go plugin
   enabled.
8. **Go interface dispatch** - a call on an interface-typed value resolves to the interface method
   declaration and is not expanded; concrete implementations are not enumerated. Values whose type
   the Go plugin cannot resolve (reflection, `any`, missing module data) stay unresolved.
9. **Go generics** - type parameters and constraints are preserved on declarations, but generic
   method bodies are not instantiated; calls through unresolved type parameters stay unresolved.
10. **Settings UI** - no IDEA preferences page; each action carries its own `maxDepth` /
   `includeModels` values.

## Plugin Descriptor

- **ID**: `com.bisotun.flowextractor`
- **Version**: `1.1.0`
- **Vendor**: `Bisotun`
- **Dependencies**: `com.intellij.modules.platform`. Java PSI is an optional content module
  (`bisotun-java`, requires `com.intellij.modules.java`). Go PSI is an optional content module
  (`bisotun-go`, requires `org.jetbrains.plugins.go`, provided by GoLand)
- **Actions**: `CopyDeepFlowAction`, `CopyShallowFlowAction`, `CopyShallowFlowWithoutModelAction`,
  `CopyMethodOnlyAction`

## Verification

- Java extraction chain unchanged: `FlowCrawler` + `ModelExtractor` are only wrapped by
  `JavaFlowExtractor`, so PSI crawling, dispatch search and model filtering behave as before
- Each caret is served by exactly one `LanguageFlowExtractor`, chosen by `FlowExtractionDispatcher`
  (`isApplicable` probe, no language mixing)
- JS/TS tier selection is a single runtime probe inside `JsTsFlowExtractor` (JavaScript plugin
  available -> PSI path, otherwise tokenizer path), shared by all actions
- Unresolved JS/TS calls are surfaced in the Markdown output instead of being silently dropped
- Go extraction is registered only when the Go plugin PSI is present, and its platform-fixture
  tests run against real Go PSI (`GoFlowExtractorTest`, `GoCrossPackageResolutionTest`)
- Unresolved Go calls are surfaced in the Markdown output instead of being matched by name
- Clipboard feedback reports the language, the call count and the model count
