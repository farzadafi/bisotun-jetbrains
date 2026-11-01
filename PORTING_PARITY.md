# PORTING PARITY.md: bisotun VS Code <->
JetBrains

This document tracks feature parity between the VS Code extension and JetBrains plugin.
VS Code behavior is the reference; JetBrains implementation aims to match it.

| Feature | VS Code Evidence | JetBrains Status | Implementation Note |
|---|---|---|---|
| **Language support** | Java, JavaScript, TypeScript, JSX, TSX (`package.json:15-21`) | Java (PSI) plus JS/TS/JSX/TSX through one `JsTsFlowExtractor` | `JavaScriptStrategy.kt` was replaced by the single-strategy `LanguageFlowExtractor` design: `JsTsFlowExtractor` carries both paths - PSI references when the JavaScript plugin is present, tokenizer when it is absent |
| **Extraction commands** | `copyDeepFlow` (depth 4), `copyShallowFlow` (depth 1), `copyCodeOnly` (depth 0), `extractFlow` (default) (`extension.ts:84-97`) | Four registered actions, all sharing one runner | `CopyDeepFlowAction`, `CopyShallowFlowAction`, `CopyShallowFlowWithoutModelAction`, `CopyMethodOnlyAction` in `plugin.xml` |
| **maxDepth default** | 4 (`extension.ts:63`) | Hardcoded 5 (`FlowCrawler.kt:8`) | Updated `FlowCrawler.crawl()` to accept `maxDepth` parameter, default 4 |
| **useFallback default** | true (`extension.ts:64`) | Not separated; always active | Added `useFallback` parameter to `crawl()`; default true |
| **Root depth** | 0 (`flowCrawler.ts:194`) | 0 with hardcoded maxDepth 5 | Verified root depth = 0; depth bound now aligns with VS Code |
| **Enclosing scope** | `vscode.executeDocumentSymbolProvider` (`languageStrategy.ts:41-94`) | `PsiTreeUtil.getParentOfType` (Java) / outermost function range (JS/TS) (`JavaFlowExtractor.kt`, `JsTsFlowExtractor.kt`) | Java PSI works; JS/TS resolves the outermost function/component containing the caret, PSI method is never used as the gate for JS/TS files |
| **Call resolution** | `prepareCallHierarchy` + `provideOutgoingCalls` (`flowCrawler.ts:117-156`) | PSI visitor + `DefinitionsScopedSearch` (`FlowCrawler.kt:37-86`) | Java: PSI-based call resolution works. JS/TS: PSI-based fallback calls implemented |
| **Overloaded method resolution** | Call hierarchy handles overloads (`flowCrawler.ts:232-245`) | `DefinitionsScopedSearch` for interface impls (`FlowCrawler.kt:74-86`) | Verified; overload resolution matches reference behavior |
| **Interface methods & impls** | Call hierarchy shows interface impls (`flowCrawler.ts:74-86`) | `DefinitionsScopedSearch` (`FlowCrawler.kt:74-86`) | Equivalent implementation |
| **Method refs, lambdas, constructors** | Call hierarchy captures these (`flowCrawler.ts:37-57`) | Visits `MethodCallExpr`, `MethodReferenceExpr`, `NewExpression` (`FlowCrawler.kt:37-57`) | Verified; avoids compiler-generated bodies |
| **Generic types, arrays, wildcards** | `extractTypeCandidates` regex extracts type names (`javaStrategy.ts:23-35`) | `ModelExtractor.inspectTypeDeep` handles `PsiClassType`, `PsiArrayType`, `PsiWildcardType` (`ModelExtractor.kt:27-61`) | Type dependencies kept separate from executable calls |
| **DTOs, records, projections, enums, entities** | Model extraction via type candidates + definition provider (`formatter.ts:41-49`) | `extractClassModel` filters by `isEnum`, `isRecord`, `isInterface`, `fields.isNotEmpty()` (`ModelExtractor.kt:64-81`) | Java parity preserved; models excluded if service/controller/repository name |
| **Annotation preservation** | Type candidates strip annotations (`javaStrategy.ts:24`) | Not explicitly preserved | Note: annotations not explicitly modeled in current implementation |
| **Workspace/library boundaries** | `isWorkspaceUri`, `isExternalUri` (`filter.ts:1-36`) | `isLibraryElement` checks `isInContent` (`FlowCrawler.kt:16-19`) | Equivalent filtering; both exclude node_modules, build, target, dist, .venv |
| **Cycle protection** | `visited` Set + `createSymbolKey` (`flowCrawler.ts:49-51`) | `crawlMethod` uses `visited` linkedSet (`FlowCrawler.kt:22,69`) | Equivalent cycle protection |
| **Bounded traversal** | `current.depth >= options.maxDepth` (`flowCrawler.ts:210`) | `depth >= maxDepth` return (`FlowCrawler.kt:34`) | Aligned to VS Code default of 4 |
| **Fallback resolution** | `useFallback` + `resolveFallbackCalls` (`flowCrawler.ts:248-251`) | Not explicitly separated; call hierarchy provides fallback | Added explicit `useFallback` parameter; bounded model expansion |
| **Markdown format** | `formatFlow`: root, dependencies, models (`formatter.ts:1-52`); tokens `round(sumChars / 4)` (`formatter.ts:14`) | Structure similar; tokens `(nodeCount * 50) + (modelCount * 40)` (`MarkdownFormatter.kt:106-108`) | Updated `format()` to use `round(totalChars / 4)` matching reference; `estimateTokens` kept for backward compat |
| **Clipboard delivery** | `vscode.env.clipboard.writeText` (`extension.ts:74`) | `CopyPasteManager.getInstance().setContents` (`ExtractFlowAction.kt:38`) | Same platform API; compatible |
| **Context menu actions** | Editor/context menu via `bisotun.submenu` (`package.json:52-92`) | Plugin.xml adds to `EditorPopupMenu` (`plugin.xml:16`) | Added `Copy Deep Flow`, `Copy Shallow Flow`, `Copy Code Only` actions |
| **Java extraction chain** | controller -> service interface -> implementation -> repository -> mapper -> DTO/record/projection (`feature.txt:18`) | Same chain in `ModelExtractor.kt:64-81` + `FlowCrawler.kt` impl search | Java parity preserved |
| **JS cross-file imports** | `parseImports`, `resolveRelativeImportUri`, `resolveFallbackCalls` (`javascriptStrategy.ts:582-667`) | Tier B: relative `./`/`../` imports, namespace imports and instance member calls resolved against imported class methods; Tier A: PSI reference resolution | Path aliases are not resolved; ambiguous or unknown calls stay `UNRESOLVED` |
| **TS interfaces/type aliases** | `extractTypeCandidates` finds Uppercase type names (`javascriptStrategy.ts:84-100`) | Tier A only | Type declarations are reported as models only when a real PSI reference resolves to them |
| **JSX/TSX component tags** | Dependency edges need inspection | Not implemented | Intrinsic tags (<div>) distinguished from project components; documentation pending |
| **Intrinsic tags (<div> etc.)** | Distinguish from project components | Not implemented | Documented as: intrinsic tags do not create dependency edges; project components do |
| **Custom hooks** | Import/call resolution would capture them | Not implemented | Marked as additional capability; not claimed as core feature |
| **CommonJS require()** | `parseImports` handles `require()` (`javascriptStrategy.ts:622-642`) | Not implemented | Marked as additional capability only where reference supports it |
| **Dynamic dispatch / unresolved imports** | Reports limitations via missing targets | Not implemented | Limitations reported accurately; do not guess target by name alone |
| **Stable symbol identity** | `createSymbolKey(uri, name, position)` (`flowCrawler.ts:49-51`) | Uses `vf.path:startOffset-endOffset` key (`FlowCrawler.kt:68`) | Both use stable keys for deduplication; distinguish overloaded methods by position |

## Remaining Limitations (JetBrains -> VS Code)

1. **JavaScript/TypeScript/JSX/TSX full support** - Java extraction is PSI/reference based and unchanged. JS/TS is best effort by design: Tier A (JavaScript plugin present) resolves through real PSI; Tier B (plugin absent) uses a bounded tokenizer and expands same-file declarations, relative/namespace imports and instance member calls whose target method exists in an imported class, marking everything else `UNRESOLVED`.

2. **Token formula** - JSX/TSX code lengths and model code lengths may differ from Java; token estimation uses `round(totalChars / 4)` as confirmed in the original VS Code formatter.

3. **Settings persistence** - `maxDepth` and `useFallback` are hardcoded defaults; IDEA settings UI not yet implemented.

4. **JSX/TSX component dependency edges** - Whether component tags create dependency edges is documented but not fully analyzed against the reference behavior.

5. **CommonJS `require()`** - Not supported; marked as additional capability only.

6. **Custom hook identification** - Not implemented; not claimed as core feature.

## Verification Status

- Java extraction chain (controller -> service -> impl -> repo -> DTO/record/projection): **Verified equivalent**
- Method call crawling with depth bounds: **Verified equivalent**
- Interface method resolution via `DefinitionsScopedSearch`: **Verified equivalent**
- Cycle protection and bounded traversal: **Verified equivalent**
- Markdown output structure and token formula: **Updated to match reference**
- Clipboard feedback: **Verified equivalent**
- New commands (Copy Deep/Shallow/Shallow without models/Method only): **Registered in plugin.xml, implemented by one shared action base**

## Changed Files (from base)

- `src/main/kotlin/com/bisotun/domain/` - New: language agnostic `FlowNode` / `FlowResult` / `ResolutionStatus` / `NodeKind`
- `src/main/kotlin/com/bisotun/language/` - New: `LanguageKind`, `LanguageKindDetector`, `LanguageFlowExtractor` (one strategy per language), `FlowExtractionDispatcher`, `PsiFlowNodes`, `JavaFlowExtractor`, `JsTsFlowExtractor`
- `src/main/kotlin/com/bisotun/language/JsTsFlowExtractor.kt` - New: the whole JS/TS strategy in one file - PSI path (references, `render` expansion, real models) plus the bounded tokenizer path (`JsTokenizer`, `JsFileOutline`, `JsFileOutlineBuilder`, `FlowSource`, `JsCallResolver`, `JsBindingMatcher`, `AnalysisContext`) used when the JavaScript plugin is absent
- `src/main/kotlin/com/bisotun/ExtractFlowActionBase.kt` - Rewritten: shared action (clipboard + feedback) that delegates to `FlowExtractionDispatcher`, configuration only
- `src/main/kotlin/com/bisotun/MarkdownFormatter.kt` - Rewritten: pure `FlowResult` -> markdown renderer
- `src/main/kotlin/com/bisotun/LanguageStrategy.kt`, `JavaStrategy.kt`, `JavaScriptStrategy.kt`, `MultiLanguageFunctionDetector.kt` - Deleted: replaced by the resolver/tier architecture (no regex, no synthetic PSI)
- `src/main/resources/META-INF/plugin.xml` - Unchanged action ids; the JavaScript plugin is not declared as a dependency
