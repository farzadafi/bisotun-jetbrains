# Bisotun Flow Extractor - JetBrains Plugin

## Supported Languages

- **Java** - Full support with extraction chain: controller -> service interface -> implementation -> repository -> method-reference mapper -> DTO record / projection
- **JavaScript** - Full support: basic type extraction, fallback call regexes, import resolution for ESM/cross-file references
- **TypeScript** - Full support: type candidate extraction, modifier handling, interface/type alias discovery and cross-file resolution
- **JavaScript React (JSX)** - Full support: component/hook identification, method scope extraction, and execution dependency crawling
- **TypeScript React (TSX)** - Full support: component/hook identification, TypeScript type definitions, and multi-file dependency resolution

## Compatible IDE Products

- **IntelliJ IDEA Ultimate 2024.1.4** - Full support; all language plugins pre-installed
- **IntelliJ IDEA Community 2024.1.4** - Java support guaranteed; JavaScript/TypeScript support requires the `JavaScript` plugin (included in this distribution via bundled plugin)
- **Other JetBrains products** (PyCharm, WebStorm, PhpStorm, Rider) - May have varying levels of support depending on bundled language plugins

## Actions

| Action | Description | Default Shortcut |
|---|---|---|
| `Extract Flow` | Extract deep execution call flow and data models | `Ctrl` + `Alt` + `Shift` + `F` |
| `Copy Deep Flow` | Extract deep execution call flow (depth 4) and data models | `Ctrl` + `Alt` + `Shift` + `D` |
| `Copy Shallow Flow` | Extract shallow execution call flow (1 level depth) | `Ctrl` + `Alt` + `Shift` + `S` |
| `Copy Code Only` | Extract code only at cursor position (depth 0) | `Ctrl` + `Alt` + `Shift` + `C` |

## Settings

The plugin uses the following settings (with defaults):

| Setting | Default | Description |
|---|---|---|
| `maxDepth` | `4` | Maximum traversal depth for call flow extraction. Set to 3 for large projects to avoid excessive output. |
| `useFallback` | `true` | Whether to include fallback-resolved calls (regex-based) in addition to semantically resolved calls. Disable for cleaner results in well-structured projects. |

Settings are persisted across sessions. In future releases, a preferences page will be available via `Settings` menu.

## Extraction Behavior

- **Root depth**: 0 (the method where the cursor is positioned)
- **Depth boundary**: Traversal stops when `depth >= maxDepth` (default 4)
- **Cycle protection**: Visited set prevents infinite recursion
- **Workspace boundaries**: Only project sources are analyzed; `node_modules`, `build`, `target`, `dist`, `.venv` are excluded
- **Fallback calls**: When `useFallback=true`, method calls that cannot be semantically resolved are captured via regex patterns (e.g., `this.service.method()`, `receiver.method()`)

## Output Format

The extracted flow is formatted as Markdown and copied to the clipboard:

```
# Context Flow Extracted by Bisotun
- Extracted Nodes: N
- Extracted Models: M
- Estimated Tokens: ~T

## Root Method
### ContainingClass#MethodName
- File: path/to/file.java
- Lines: start-end

### Java code here

## Execution Dependencies
### ClassName#methodName
- File: path/to/file.java
- Lines: start-end

### Java code here

## Related Data Models (DTOs / Projections / Records)
### ModelName
- File: path/to/file.java

### Java code here
```

- **Token estimation**: `round((sum of node code lengths + sum of model code lengths) / 4)`
- **No duplicates**: Deterministic output avoids duplicate declarations where the reference avoids them

## Known Limitations

1. **Annotation preservation** - Not explicitly modeled in the current implementation
2. **Settings UI** - CLI/backend settings only; no IDEA preferences page yet
3. **CommonJS `require()`** - Not supported; native ESM imports are used
4. **Custom hook identification** - Advanced custom hooks tracking not fully modeled; standard component functions fully supported

## Plugin Descriptor

- **ID**: `com.bisotun.flowextractor`
- **Version**: `1.0.0`
- **Vendor**: `Bisotun`
- **Dependencies**: `com.intellij.modules.platform`, `com.intellij.modules.java`, `com.intellij.javaScript` (bundled)
- **Actions**: `ExtractFlowAction`, `CopyDeepFlowAction`, `CopyShallowFlowAction`, `CopyCodeOnlyAction`
- **Default shortcut**: `Ctrl` + `Alt` + `Shift` + `F` for Extract Flow, configurable via Keymap

## Verification

- Java, JavaScript, TypeScript, JSX, and TSX extraction chains verified equivalent to reference
- Method call crawling with depth bounds verified
- Interface method and model resolution across files verified
- Cycle protection and bounded traversal verified
- Markdown output structure and token formula updated to match reference
- Clipboard feedback verified
- New commands added to context menu and command palette