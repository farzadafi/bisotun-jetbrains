package com.bisotun.language

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.io.File

/**
 * Python strategy and the single home of every Python extraction concern.
 *
 * The IntelliJ Python plugin is optional for the IntelliJ IDEA Community target, so Python flow
 * extraction mirrors the JS/TS tokenizer tier: a hand written tokenizer, an indentation aware file
 * outline and a conservative call resolver. No Python PSI class is referenced anywhere, so this
 * strategy works identically with and without the Python plugin installed.
 *
 * The tokenizer understands `def` / `async def`, classes and methods (including `self` / `cls`,
 * `@staticmethod`, `@classmethod`, `@property` and decorators), indentation based scopes and
 * continuations, type hints, imports and aliases, and type aliases used to report honest models.
 * Resolving a call is conservative: a declaration is only expanded when it can actually be found by
 * name in the current file or in a file reachable through an import. Anything else is emitted as an
 * [ResolutionStatus.UNRESOLVED] node; nothing is ever fabricated.
 */
class PythonFlowExtractor(private val project: Project) : LanguageFlowExtractor {

    private val log = Logger.getInstance(PythonFlowExtractor::class.java)
    private val callResolver = PythonCallResolver()

    override val supportedLanguages: Set<LanguageKind> = setOf(LanguageKind.PYTHON)

    /** One expandable scope of the tokenizer strategy: a function, a class-body block or the module. */
    private data class TokenizerScope(
        val startOffset: Int,
        val endOffset: Int,
        val displayName: String,
        val kind: NodeKind
    )

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = LanguageKindDetector.detect(file)
        val caretOffset = caretOffset(file, editor)
        val context = PythonAnalysisContext(project.basePath)
        val source = context.sourceForFile(file)
            ?: return FlowExtractionOutcome.failure(languageKind, "Unable to read this file for tokenizing.")
        val scope = resolveScope(source.outline, caretOffset)
            ?: return FlowExtractionOutcome.failure(languageKind, NO_SCOPE_MESSAGE)
        val result = buildTokenizedFlow(source, scope, maxDepth, includeModels, context)
        return FlowExtractionOutcome(result, source.languageKind)
    }


    private fun caretOffset(file: PsiFile, editor: Editor): Int {
        val textLength = try {
            file.textLength
        } catch (t: Throwable) {
            0
        }
        return editor.caretModel.offset.coerceIn(0, maxOf(textLength - 1, 0))
    }

    /**
     * Caret -> scope, decided on real source offsets only (never on PSI). Order of preference:
     * innermost function/method, class body block, then module scope - which is only offered when
     * the module really declares something to crawl.
     */
    private fun resolveScope(outline: PythonFileOutline, caretOffset: Int): TokenizerScope? {
        val function = outline.functionContaining(caretOffset)
        if (function != null) {
            return TokenizerScope(function.startOffset, function.endOffset, function.displayName, function.kind)
        }
        val block = outline.blockContaining(caretOffset)
        if (block != null) {
            return TokenizerScope(block.startOffset, block.endOffset, BLOCK_SCOPE_NAME, NodeKind.UNKNOWN)
        }
        val moduleHasContent = outline.functions.isNotEmpty() ||
            outline.callSitesIn(0, outline.text.length).isNotEmpty()
        return if (moduleHasContent) {
            TokenizerScope(0, outline.text.length, MODULE_SCOPE_NAME, NodeKind.UNKNOWN)
        } else {
            null
        }
    }

    private fun buildTokenizedFlow(
        source: PythonFlowSource,
        scope: TokenizerScope,
        maxDepth: Int,
        includeModels: Boolean,
        context: PythonAnalysisContext
    ): FlowResult {
        val rootNode = nodeFor(source, scope, ResolutionStatus.RESOLVED)
        val visitedNodeIds = mutableSetOf(rootNode.id)
        val roots = mutableListOf<ReaderScope>()

        if (maxDepth > 0) {
            var frontier = listOf(ReaderScope(source, scope))
            var depth = 1
            while (depth <= maxDepth && frontier.isNotEmpty()) {
                val next = mutableListOf<ReaderScope>()
                for (handle in frontier) {
                    val handleScope = handle.scope ?: continue
                    val sites = handle.source.outline.callSitesIn(handleScope.startOffset, handleScope.endOffset)
                    for (site in sites) {
                        val resolved = resolveTokenizedCall(handle.source, site, context)
                        if (resolved == null) {
                            val node = unresolvedNode(handle.source, site)
                            if (visitedNodeIds.add(node.id)) roots.add(ReaderScope(handle.source, null, node))
                            continue
                        }
                        val definition = resolved.definition
                        if (handle.source == resolved.source &&
                            definition.name == rootName(scope) &&
                            definition.startOffset == scope.startOffset
                        ) {
                            continue
                        }
                        val targetScope = TokenizerScope(
                            definition.startOffset,
                            definition.endOffset,
                            definition.displayName,
                            definition.kind
                        )
                        val node = nodeFor(resolved.source, targetScope, ResolutionStatus.RESOLVED)
                        if (!visitedNodeIds.add(node.id)) continue
                        roots.add(ReaderScope(resolved.source, targetScope))
                        if (depth < maxDepth) next.add(ReaderScope(resolved.source, targetScope))
                    }
                }
                frontier = next
                depth++
            }
        }

        val nodes = roots.filter { it.node == null }.map { nodeFor(it.source, it.scope!!, ResolutionStatus.RESOLVED) } +
            roots.filter { it.node != null }.map { it.node!! }
        val models = if (includeModels) collectModels(listOf(ReaderScope(source, scope)) + roots, context) else emptyList()
        return FlowResult(
            root = rootNode,
            nodes = nodes,
            models = models,
            warnings = emptyList()
        )
    }

    private fun rootName(scope: TokenizerScope): String = scope.displayName.removeSuffix("()")

    private fun resolveTokenizedCall(
        source: PythonFlowSource,
        site: PythonCallSite,
        context: PythonAnalysisContext
    ): PythonResolvedCall? = callResolver.resolve(source, site) { from, specifier ->
        importedSource(from, specifier, context)
    }

    private fun importedSource(
        source: PythonFlowSource,
        specifier: String,
        context: PythonAnalysisContext
    ): PythonFlowSource? {
        val targetPath = context.resolveSpecifier(source.filePath, specifier) ?: return null
        return context.sourceForPath(targetPath, source.languageKind)
    }

    private fun collectModels(roots: List<ReaderScope>, context: PythonAnalysisContext): List<FlowNode> {
        val models = LinkedHashMap<String, FlowNode>()
        for (handle in roots) {
            val scope = handle.scope ?: continue
            for (name in handle.source.outline.identifiersIn(scope.startOffset, scope.endOffset)) {
                val localType = handle.source.outline.typeNamed(name)
                if (localType != null) {
                    addModel(models, handle.source, localType)
                    continue
                }
                val binding = handle.source.outline.importFor(name) ?: continue
                val importedName = binding.importedName ?: continue
                val targetSource = importedSource(handle.source, binding.specifier, context) ?: continue
                val importedType = targetSource.outline.typeNamed(importedName) ?: continue
                addModel(models, targetSource, importedType)
            }
        }
        return models.values.toList()
    }

    private fun addModel(models: MutableMap<String, FlowNode>, source: PythonFlowSource, type: PythonTypeDef) {
        val start = type.startOffset.coerceIn(0, source.text.length)
        val end = type.endOffset.coerceIn(start, source.text.length)
        val id = "${source.filePath}:$start-$end"
        if (models.containsKey(id)) return
        models[id] = FlowNode(
            id = id,
            displayName = type.name,
            filePath = jsRelativePath(source.filePath, project.basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = ResolutionStatus.RESOLVED,
            kind = NodeKind.MODEL,
            code = source.text.substring(start, end).trim()
        )
    }

    private fun nodeFor(source: PythonFlowSource, scope: TokenizerScope, status: ResolutionStatus): FlowNode {
        val start = scope.startOffset.coerceIn(0, source.text.length)
        val end = scope.endOffset.coerceIn(start, source.text.length)
        return FlowNode(
            id = "${source.filePath}:$start-$end",
            displayName = scope.displayName,
            filePath = jsRelativePath(source.filePath, project.basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = status,
            kind = scope.kind,
            code = source.text.substring(start, end).trim()
        )
    }

    private fun unresolvedNode(source: PythonFlowSource, site: PythonCallSite): FlowNode {
        val start = site.offset.coerceIn(0, source.text.length)
        val end = (start + site.rawText.length).coerceAtMost(source.text.length)
        return FlowNode(
            id = "${source.filePath}:$start-$end",
            displayName = "${site.rawText}()",
            filePath = jsRelativePath(source.filePath, project.basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = ResolutionStatus.UNRESOLVED,
            kind = NodeKind.UNKNOWN
        )
    }

    private data class ReaderScope(
        val source: PythonFlowSource,
        val scope: TokenizerScope?,
        val node: FlowNode? = null
    )

    private companion object {
        const val NO_SCOPE_MESSAGE =
            "Please place the caret inside a function, class method or class body."
        const val BLOCK_SCOPE_NAME = "block()"
        const val MODULE_SCOPE_NAME = "module()"
    }
}

// ---------------------------------------------------------------------------------------
// Python tokenizer - part of the single Python extraction strategy
// ---------------------------------------------------------------------------------------

/** Kinds of the tokens produced by [PythonTokenizer]. */
internal enum class PythonTokenType { IDENTIFIER, KEYWORD, STRING, NUMBER, PUNCTUATION }

/** A single token of a Python source file. */
internal data class PythonToken(
    val type: PythonTokenType,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val indent: Int = 0
)

/**
 * A function-like declaration discovered by the tokenizer tier of the Python strategy.
 *
 * [startOffset]/[endOffset] bound the declaration in its file; [nameOffset] points at the declared
 * name so call detection can avoid reporting a declaration as a call. [indent] is the indentation
 * column (number of leading spaces) of the `def` line - it is the Python analogue of the brace scope
 * and lets `callSitesIn` skip nested function bodies.
 */
internal data class PythonFunctionDef(
    val name: String,
    val displayName: String,
    val startOffset: Int,
    val endOffset: Int,
    val nameOffset: Int,
    val indent: Int,
    val kind: NodeKind
)

/**
 * One imported binding: `import a.b as c` -> localName `c`, importedName `a.b`, isModule true;
 * `from m import x as y` -> localName `y`, importedName `x`, isModule false.
 */
internal data class PythonImportBinding(
    val localName: String,
    val importedName: String?,
    val specifier: String,
    val isModule: Boolean,
    val isRelative: Boolean = false
)

internal data class PythonTypeDef(
    val name: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * A call site inside a function body. [rawText] keeps the full chain (`service.users.list`) so an
 * unresolved node still shows what the code actually called.
 */
internal data class PythonCallSite(
    val name: String,
    val qualifier: String?,
    val rootIdentifier: String,
    val offset: Int,
    val rawText: String
)



/**
 * A minimal Python tokenizer.
 *
 * Purpose: stay dependency-free (works on IC builds without any Python plugin) and provide the
 * tokens needed for outlining, call detection and import reading. It understands strings including
 * f-strings and triple-quoted strings, comments, indentation (leading whitespace per line) and
 * bracket/backslash continuation lines. It does NOT attempt full Python parsing - anything it cannot
 * understand is simply consumed conservatively so that calls are never invented.
 */
internal class PythonTokenizer(private val text: String) {

    fun tokenize(): List<PythonToken> {
        val result = ArrayList<PythonToken>()
        var i = 0
        val n = text.length
        var line = 1
        var lineStart = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '\n' -> {
                    i++
                    line++
                    lineStart = i
                }
                c == ' ' || c == '\t' || c == '\r' -> {
                    i++
                }
                c == '#' -> {
                    i = skipComment(i)
                }
                c == '\'' || c == '"' || isStringPrefix(text, i, n) -> {
                    val start = i
                    val end = consumeString(text, i, n)
                    val raw = text.substring(start, end)
                    result.add(PythonToken(PythonTokenType.STRING, raw, start, end, line, indentOf(text, lineStart, i)))
                    i = end
                }
                isIdentifierStart(c) -> {
                    val start = i
                    while (i < n && isIdentifierPart(text[i])) i++
                    val raw = text.substring(start, i)
                    val type = if (raw in PYTHON_KEYWORDS) PythonTokenType.KEYWORD else PythonTokenType.IDENTIFIER
                    result.add(PythonToken(type, raw, start, i, line, indentOf(text, lineStart, start)))
                }
                c.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                    val raw = text.substring(start, i)
                    // fix floating-point numbers that swallowed a trailing dot chain
                    result.add(PythonToken(PythonTokenType.NUMBER, raw, start, i, line, indentOf(text, lineStart, start)))
                }
                else -> {
                    val start = i
                    i++
                    val raw = text.substring(start, i)
                    result.add(PythonToken(PythonTokenType.PUNCTUATION, raw, start, i, line, indentOf(text, lineStart, start)))
                }
            }
        }
        return result
    }

    private fun skipComment(i: Int): Int {
        var j = i
        while (j < text.length && text[j] != '\n') j++
        return j
    }

    private fun indentOf(s: String, lineStart: Int, tokenStart: Int): Int {
        var col = 0
        var k = lineStart
        while (k < tokenStart && col < 255) {
            when (s[k]) {
                ' ' -> col++
                '\t' -> col += 4
                else -> return col
            }
            k++
        }
        return col
    }

    private companion object {
        val PYTHON_KEYWORDS = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import",
            "in", "is", "lambda", "None", "nonlocal", "not", "or", "pass", "raise", "return",
            "True", "try", "while", "with", "yield"
        )
    }
}

/** Returns true if the text starting at [i] begins a string literal (with an optional r/b/u/f prefix). */
private fun isStringPrefix(s: String, i: Int, n: Int): Boolean {
    var j = i
    var prefixChars = 0
    while (j < n && prefixChars < 3) {
        val ch = s[j]
        if (ch in "rubfRUBF") { j++; prefixChars++ } else break
    }
    return prefixChars > 0 && j < n && (s[j] == '\'' || s[j] == '"')
}

/** Consumes one string literal (including triple-quoted and prefixed). Returns the index just after it. */
private fun consumeString(s: String, i: Int, n: Int): Int {
    // skip prefix letters (r, b, u, f and combinations)
    var j = i
    while (j < n && s[j] in "rubfRUBF") j++
    if (j >= n) return i + 1
    val quote = s[j]
    if (quote != '\'' && quote != '"') return i + 1
    val triple = j + 2 < n && s[j + 1] == quote && s[j + 2] == quote
    j = j + if (triple) 3 else 1
    while (j < n) {
        if (s[j] == '\\') { j += 2; continue }
        if (triple) {
            if (j + 2 < n && s[j] == quote && s[j + 1] == quote && s[j + 2] == quote) return j + 3
        } else {
            if (s[j] == quote) return j + 1
            if (s[j] == '\n') return j + 1  // unterminated single-line string; do not run off the line
        }
        j++
    }
    return n
}

private fun isIdentifierStart(c: Char): Boolean = c == '_' || c.isLetter()
private fun isIdentifierPart(c: Char): Boolean = c == '_' || c.isLetterOrDigit()


// ---------------------------------------------------------------------------------------
// Python outline - indentation aware, mirrors the JS/TS outline API for Python
// ---------------------------------------------------------------------------------------

/**
 * A parsed Python file as the tokenizer tier sees it.
 *
 * Deliberately platform free: the tokenizer tier and its call resolver only need the file path,
 * its text, the parsed [PythonFileOutline] and the detected language, so this type can be built
 * both from PSI (open editors) and from disk (imports).
 */
internal data class PythonFlowSource(
    val filePath: String,
    val text: String,
    val outline: PythonFileOutline,
    val languageKind: LanguageKind
)

/** A call target found by [PythonCallResolver] across files. */
internal data class PythonResolvedCall(
    val source: PythonFlowSource,
    val definition: PythonFunctionDef
)

/**
 * Indentation based outline of a Python file.
 *
 * The Python analogue of [JsFileOutline]: functions/classes are found by their `def`/`class`
 * statement and their span extends to the next statement whose indentation is not deeper, which is
 * exactly the Python block rule. Nothing that cannot be parsed unambiguously is reported.
 */
internal class PythonFileOutline(
    val text: String,
    val functions: List<PythonFunctionDef>,
    val types: List<PythonTypeDef>,
    val imports: List<PythonImportBinding>,
    internal val tokens: List<PythonToken>
) {


    /** Innermost (named) function containing [offset]; prefers the latest start. */
    fun functionContaining(offset: Int): PythonFunctionDef? = functions
        .filter { offset >= it.startOffset && offset <= it.endOffset }
        .maxByOrNull { it.startOffset }

    fun functionNamed(name: String): PythonFunctionDef? = functions.firstOrNull { it.name == name }

    fun typeNamed(name: String): PythonTypeDef? = types.firstOrNull { it.name == name }

    /** Functions declared inside [type]'s span: class methods. */
    fun functionsIn(type: PythonTypeDef): List<PythonFunctionDef> =
        functions.filter { it.startOffset >= type.startOffset && it.endOffset <= type.endOffset }

    /** Method named [name] declared by [type], or null when that type does not declare it. */
    fun methodIn(type: PythonTypeDef, name: String): PythonFunctionDef? =
        functionsIn(type).firstOrNull { it.name == name }

    fun importFor(localName: String): PythonImportBinding? =
        imports.firstOrNull { it.localName == localName }

    /** Identifier names used inside `[startOffset, endOffset)`; never resolves anything by itself. */
    fun identifiersIn(startOffset: Int, endOffset: Int): Set<String> {
        val names = LinkedHashSet<String>()
        for (token in tokens) {
            if (token.startOffset < startOffset || token.startOffset >= endOffset) continue
            if (token.type == PythonTokenType.IDENTIFIER) names.add(token.text)
        }
        return names
    }

    /**
     * Calls whose callee starts inside `[startOffset, endOffset)`. Member chains are collapsed to the
     * last segment, so `service.users.list()` yields name `list`, qualifier `users`, root `service`.
     * When [skipNestedFunctions] is true the bodies of nested declarations are skipped, so a module
     * scope lists its own statements instead of every call made by the functions it holds.
     */
    fun callSitesIn(
        startOffset: Int,
        endOffset: Int,
        skipNestedFunctions: Boolean = true
    ): List<PythonCallSite> {
        val sites = LinkedHashMap<Int, PythonCallSite>()
        val declarationNames = functions.mapTo(HashSet()) { it.nameOffset }
        val nested = if (skipNestedFunctions) {
            functions.filter { it.startOffset > startOffset && it.endOffset <= endOffset }
        } else {
            emptyList()
        }
        var i = firstTokenIndexAtOrAfter(startOffset)
        while (i < tokens.size && tokens[i].startOffset < endOffset) {
            val token = tokens[i]
            if (token.type != PythonTokenType.IDENTIFIER ||
                nested.any { token.startOffset in it.startOffset..it.endOffset }
            ) {
                i++
                continue
            }
            if (i > 0 && tokens[i - 1].text == "@") {
                i++
                continue
            }
            val chain = mutableListOf(token.text)
            var last = i
            while (true) {
                val separator = last + 1
                val next = separator + 1
                if (next >= tokens.size) break
                if (tokens[separator].text == "." && isNameToken(next)) {
                    chain.add(tokens[next].text)
                    last = next
                } else {
                    break
                }
            }
            val afterChain = last + 1
            val isCall = afterChain < tokens.size &&
                tokens[afterChain].type == PythonTokenType.PUNCTUATION &&
                tokens[afterChain].text == "("
            if (isCall) {
                val calleeIndex = last
                if (!declarationNames.contains(tokens[calleeIndex].startOffset)) {
                    sites.putIfAbsent(
                        tokens[i].startOffset,
                        PythonCallSite(
                            name = chain.last(),
                            qualifier = if (chain.size > 1) chain[chain.size - 2] else null,
                            rootIdentifier = chain.first(),
                            offset = tokens[i].startOffset,
                            rawText = chain.joinToString(".")
                        )
                    )
                }
            }
            i = last + 1
        }
        return sites.values.toList()
    }

    /** Innermost class body containing [offset]; used when the caret is inside a class but outside a method. */
    fun blockContaining(offset: Int): PythonTypeDef? {
        var best: PythonTypeDef? = null
        for (type in types) {
            val start = type.startOffset
            val end = type.endOffset
            if (offset < start || offset > end) continue
            if (best == null || start > best.startOffset) best = type
        }
        return best
    }

    private fun firstTokenIndexAtOrAfter(offset: Int): Int {
        var low = 0
        var high = tokens.size
        while (low < high) {
            val middle = (low + high) / 2
            if (tokens[middle].startOffset < offset) low = middle + 1 else high = middle
        }
        return low
    }

    private fun isNameToken(index: Int): Boolean {
        val token = tokens.getOrNull(index) ?: return false
        return token.type == PythonTokenType.IDENTIFIER
    }

    companion object {
        fun parse(text: String): PythonFileOutline = PythonFileOutlineBuilder(text).build()
    }
}
/**
 * Parses the token stream into an indentation based [PythonFileOutline].
 *
 * Works on physical lines grouped by the tokenizer (which already records the indent of each line
 * and consumes comments/strings). Statements are the Python-logical statements created by joining
 * physical lines whose parentheses are unbalanced or that end with a backslash. `def`/`class`
 * declarations and `import`/`from` statements are recognized from the statement head; every block's
 * extent is then the classic Python rule - everything up to the next statement whose indentation is
 * not deeper.
 */


private class PythonFileOutlineBuilder(private val text: String) {

    private val tokens = PythonTokenizer(text).tokenize()
    private val functions = mutableListOf<FunctionRecord>()
    private val types = mutableListOf<TypeRecord>()
    private val imports = mutableListOf<PythonImportBinding>()

    fun build(): PythonFileOutline {
        val statements = logicalStatements()
        parseStatements(statements)
        return PythonFileOutline(text, materializedFunctions(), materializedTypes(), imports, tokens)
    }

    /** A Python-logical statement (one or more physical lines joined by brackets or `\`). */
    private data class Statement(
        val startIndex: Int,
        val endIndex: Int,
        val startOffset: Int,
        val indent: Int
    )

    private class FunctionRecord(
        val name: String,
        val startOffset: Int,
        val nameOffset: Int,
        val indent: Int,
        var endOffset: Int
    ) {
        var kind: NodeKind = NodeKind.FUNCTION
    }

    private class TypeRecord(
        val name: String,
        val startOffset: Int,
        val indent: Int,
        var endOffset: Int
    )

    private sealed interface OpenBody {
        val indent: Int
    }

    private class OpenFunction(val record: FunctionRecord) : OpenBody {
        override val indent: Int = record.indent
    }

    private class OpenType(val record: TypeRecord) : OpenBody {
        override val indent: Int = record.indent
    }

    private fun logicalStatements(): List<Statement> {
        val statements = mutableListOf<Statement>()
        var balance = 0
        var startIndex = -1
        var startOffset = -1
        var indent = 0

        var consume = 0
        while (consume < tokens.size) {
            val lineNumber = tokens[consume].line
            val lineTokens = mutableListOf<PythonToken>()
            while (consume < tokens.size && tokens[consume].line == lineNumber) {
                lineTokens.add(tokens[consume])
                consume++
            }
            if (lineTokens.isEmpty()) continue
            val first = consume - lineTokens.size
            val last = consume - 1
            if (startIndex < 0) {
                startIndex = first
                startOffset = lineTokens.first().startOffset
                indent = lineTokens.first().indent
            }
            for (t in lineTokens) {
                when (t.text) {
                    "(", "[", "{" -> balance++
                    ")", "]", "}" -> balance = (balance - 1).coerceAtLeast(0)
                }
            }
            val continues = balance > 0 || lineTokens.last().text == "\\"
            if (!continues) {
                statements.add(Statement(startIndex, last + 1, startOffset, indent))
                startIndex = -1
                startOffset = -1
            }
        }
        if (startIndex >= 0) {
            statements.add(Statement(startIndex, tokens.size, startOffset, indent))
        }
        return statements
    }
    private fun parseStatements(statements: List<Statement>) {
        val stack = ArrayDeque<OpenBody>()
        for (statement in statements) {
            closeOpenBodies(stack, statement.startOffset, statement.indent)
            handleStatement(statement, stack)
        }
        val eof = text.length
        while (stack.isNotEmpty()) {
            when (val open = stack.removeLast()) {
                is OpenFunction -> open.record.endOffset = eof
                is OpenType -> open.record.endOffset = eof
            }
        }
    }

    private fun closeOpenBodies(stack: ArrayDeque<OpenBody>, nextOffset: Int, nextIndent: Int) {
        while (stack.isNotEmpty() && stack.last().indent >= nextIndent) {
            when (val open = stack.removeLast()) {
                is OpenFunction -> open.record.endOffset = nextOffset
                is OpenType -> open.record.endOffset = nextOffset
            }
        }
    }

    private fun handleStatement(statement: Statement, stack: ArrayDeque<OpenBody>) {
        val first = tokens.getOrNull(statement.startIndex) ?: return
        if (first.text == "@") return  // decorator; not a declaration body itself
        val keyword = firstKeyword(statement) ?: return
        when (keyword) {
            "def" -> handleDef(statement, stack)
            "async" -> handleAsyncDef(statement, stack)
            "class" -> handleClass(statement, stack)
            "import" -> handleImport(statement)
            "from" -> handleFrom(statement)
        }
    }

    private fun firstKeyword(statement: Statement): String? {
        for (i in statement.startIndex until statement.endIndex) {
            val token = tokens[i]
            if (token.type == PythonTokenType.KEYWORD) return token.text
        }
        return null
    }

    private fun handleAsyncDef(statement: Statement, stack: ArrayDeque<OpenBody>) {
        handleDef(statement, stack)
    }

    private fun handleDef(statement: Statement, stack: ArrayDeque<OpenBody>) {
        val defIndex = indexOfKeyword(statement, "def") ?: return
        val nameToken = tokens.getOrNull(defIndex + 1) ?: return
        if (nameToken.type != PythonTokenType.IDENTIFIER) return
        val endOffset = statementEndOffset(statement)
        val record = FunctionRecord(
            name = nameToken.text,
            startOffset = tokens[defIndex].startOffset,
            nameOffset = nameToken.startOffset,
            indent = statement.indent,
            endOffset = endOffset
        )
        functions.add(record)
        stack.addLast(OpenFunction(record))
    }

    private fun handleClass(statement: Statement, stack: ArrayDeque<OpenBody>) {
        val classIndex = indexOfKeyword(statement, "class") ?: return
        val nameToken = tokens.getOrNull(classIndex + 1) ?: return
        if (nameToken.type != PythonTokenType.IDENTIFIER) return
        val endOffset = statementEndOffset(statement)
        val record = TypeRecord(
            name = nameToken.text,
            startOffset = tokens[classIndex].startOffset,
            indent = statement.indent,
            endOffset = endOffset
        )
        types.add(record)
        stack.addLast(OpenType(record))
    }

    private fun indexOfKeyword(statement: Statement, keyword: String): Int? {
        for (i in statement.startIndex until statement.endIndex) {
            val token = tokens[i]
            if (token.type == PythonTokenType.KEYWORD && token.text == keyword) return i
        }
        return null
    }

    private fun statementEndOffset(statement: Statement): Int {
        if (statement.startIndex >= statement.endIndex) return statement.startOffset
        return tokens[statement.endIndex - 1].endOffset
    }

    private fun handleImport(statement: Statement) {
        val kwIndex = indexOfKeyword(statement, "import") ?: return
        var i = kwIndex + 1
        while (i < statement.endIndex) {
            val first = tokens[i]
            if (first.type == PythonTokenType.PUNCTUATION && first.text == ",") {
                i++
                continue
            }
            val parts = mutableListOf<String>()
            while (i < statement.endIndex) {
                val t = tokens[i]
                when {
                    t.type == PythonTokenType.IDENTIFIER -> { parts.add(t.text); i++ }
                    t.type == PythonTokenType.PUNCTUATION && t.text == "." -> i++
                    else -> break
                }
            }
            if (parts.isEmpty()) break
            var local = parts.first()
            if (i < statement.endIndex && tokens[i].type == PythonTokenType.KEYWORD && tokens[i].text == "as") {
                val alias = tokens.getOrNull(i + 1)
                if (alias != null && alias.type == PythonTokenType.IDENTIFIER) {
                    local = alias.text
                    i += 2
                }
            }
            val dotted = parts.joinToString(".")
            addImport(local, dotted, dotted, isModule = true)
            while (i < statement.endIndex &&
                !(tokens[i].type == PythonTokenType.PUNCTUATION && tokens[i].text == ",")
            ) {
                i++
            }
        }
    }

    private fun handleFrom(statement: Statement) {
        val fromIndex = indexOfKeyword(statement, "from") ?: return
        val importIndex = indexOfKeyword(statement, "import") ?: return
        var i = fromIndex + 1
        var relative = 0
        while (i < importIndex) {
            val t = tokens[i]
            if (t.type == PythonTokenType.PUNCTUATION && t.text == ".") { relative++; i++ } else break
        }
        val parts = mutableListOf<String>()
        while (i < importIndex) {
            val t = tokens[i]
            when {
                t.type == PythonTokenType.IDENTIFIER -> { parts.add(t.text); i++ }
                t.type == PythonTokenType.PUNCTUATION && t.text == "." -> i++
                else -> break
            }
        }
        val module = (if (relative > 0) ".".repeat(relative) else "") + parts.joinToString(".")
        var j = importIndex + 1
        while (j < statement.endIndex) {
            val t = tokens[j]
            if (t.type == PythonTokenType.IDENTIFIER) {
                val imported = t.text
                var local = imported
                if (j + 2 < statement.endIndex &&
                    tokens[j + 1].type == PythonTokenType.KEYWORD && tokens[j + 1].text == "as"
                ) {
                    val alias = tokens.getOrNull(j + 2)
                    if (alias != null && alias.type == PythonTokenType.IDENTIFIER) local = alias.text
                    j += 3
                } else {
                    j++
                }
                addImport(local, imported, module, isModule = false, isRelative = relative > 0)
            } else {
                j++
            }
        }
    }

    private fun addImport(
        local: String,
        imported: String?,
        specifier: String,
        isModule: Boolean,
        isRelative: Boolean = false
    ) {
        if (imports.none { it.localName == local && it.specifier == specifier }) {
            imports.add(PythonImportBinding(local, imported, specifier, isModule, isRelative))
        }
    }

    private fun materializedFunctions(): List<PythonFunctionDef> = functions.map { record ->
        PythonFunctionDef(
            name = record.name,
            displayName = "${record.name}()",
            startOffset = record.startOffset,
            endOffset = record.endOffset.coerceIn(record.startOffset, text.length),
            nameOffset = record.nameOffset,
            indent = record.indent,
            kind = if (isClassMethod(record)) NodeKind.METHOD else record.kind
        )
    }

    private fun materializedTypes(): List<PythonTypeDef> = types.map { record ->
        PythonTypeDef(
            name = record.name,
            startOffset = record.startOffset,
            endOffset = record.endOffset.coerceIn(record.startOffset, text.length)
        )
    }
    private fun isClassMethod(record: FunctionRecord): Boolean =
        types.any { record.startOffset > it.startOffset && record.endOffset <= it.endOffset }
}



// ---------------------------------------------------------------------------------------
// Python call resolver - cross file resolution, mirrors the JS/TS resolver tiering
// ---------------------------------------------------------------------------------------

/**
 * Cross file call resolution for the tokenizer tier.
 *
 * Resolution order, most to least confident (mirrors the JS/TS [com.bisotun.language.JsCallResolver]):
 *
 *  1. a declaration in the same file - plain `notify()` and `self._helper()`/`cls._helper()`;
 *  2. an exact import binding - `from module import ClassName` and `import module`;
 *  3. an imported binding whose name relates to the instance qualifier
 *     (`payment_service` -> `PaymentService`, `gateway` -> `PaymentGateway`);
 *  4. any imported file that declares a class method (preferred) or module function with the
 *     called name, when exactly one target file qualifies.
 *
 * Every step resolves through the [PythonFileOutline] of the target file, so a resolution can never
 * point at invented source. Calls that cannot be matched stay unresolved.
 */
internal class PythonCallResolver {

    fun resolve(
        source: PythonFlowSource,
        site: PythonCallSite,
        importedSource: (PythonFlowSource, String) -> PythonFlowSource?
    ): PythonResolvedCall? {
        val qualifier = site.qualifier
        if (qualifier == null || qualifier == SELF || qualifier == CLS) {
            val local = source.outline.functionNamed(site.name)
            if (local != null && local.startOffset != site.offset) return PythonResolvedCall(source, local)
            if (qualifier == SELF || qualifier == CLS) return null
        }
        if (qualifier == null) {
            val binding = source.outline.importFor(site.name) ?: return null
            return resolveBinding(source, binding, importedSource)
        }
        return resolveMemberCall(source, site, qualifier, importedSource)
    }

    /** `service.processPayment()` / `self.client.processPayment()`. */
    private fun resolveMemberCall(
        source: PythonFlowSource,
        site: PythonCallSite,
        qualifier: String,
        importedSource: (PythonFlowSource, String) -> PythonFlowSource?
    ): PythonResolvedCall? {
        localMember(source, qualifier, site.name)?.let { return it }

        val exact = source.outline.importFor(qualifier)
        if (exact != null) {
            resolveBindingMember(source, exact, site.name, importedSource)?.let { return it }
        }

        val fuzzyBindings = source.outline.imports
            .filter { it !== exact }
            .map { it to PythonBindingMatcher.bindingScore(qualifier, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        for ((binding, _) in fuzzyBindings) {
            resolveBindingMember(source, binding, site.name, importedSource)?.let { return it }
        }

        // Python builtins never point at an import.
        if (qualifier in PYTHON_GLOBALS) return null
        return resolveByImportedDeclaration(source, qualifier, site.name, importedSource)
    }

    /** Class method named [memberName] declared in this file for an instance called [qualifier]. */
    private fun localMember(
        source: PythonFlowSource,
        qualifier: String,
        memberName: String
    ): PythonResolvedCall? {
        val outline = source.outline
        val bestType = outline.types
            .map { type -> type to PythonBindingMatcher.score(qualifier, type.name) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
        if (bestType != null) {
            outline.methodIn(bestType, memberName)?.let { return PythonResolvedCall(source, it) }
        }
        val candidates = outline.types.mapNotNull { type ->
            outline.methodIn(type, memberName)
        }.distinctBy { it.startOffset }
        return if (candidates.size == 1) PythonResolvedCall(source, candidates.single()) else null
    }

    /** `from module import ClassName` used directly, e.g. `ClassName.method()`. */
    private fun resolveBinding(
        source: PythonFlowSource,
        binding: PythonImportBinding,
        importedSource: (PythonFlowSource, String) -> PythonFlowSource?
    ): PythonResolvedCall? {
        val targetSource = importedSource(source, binding.specifier) ?: return null
        val definition = when {
            binding.importedName != null -> targetSource.outline.functionNamed(binding.importedName)
            else -> null
        } ?: return null
        return PythonResolvedCall(targetSource, definition)
    }

    /**
     * Resolves [memberName] inside the declaration [binding] points at: the module function first,
     * then a class method when the target has no such module function. A class/import that does not
     * declare [memberName] keeps the call unresolved instead of falling back to an unrelated module
     * function.
     */
    private fun resolveBindingMember(
        source: PythonFlowSource,
        binding: PythonImportBinding,
        memberName: String,
        importedSource: (PythonFlowSource, String) -> PythonFlowSource?
    ): PythonResolvedCall? {
        val targetSource = importedSource(source, binding.specifier) ?: return null
        val target = targetSource.outline
        val moduleFunc = target.functionNamed(memberName)
        if (moduleFunc != null) return PythonResolvedCall(targetSource, moduleFunc)

        val type = resolveImportedType(target, binding) ?: return null
        val method = target.methodIn(type, memberName) ?: return null
        return PythonResolvedCall(targetSource, method)
    }

    /** The class a `from` binding points at. */
    private fun resolveImportedType(outline: PythonFileOutline, binding: PythonImportBinding): PythonTypeDef? {
        if (binding.isModule) return null
        binding.importedName?.let { outline.typeNamed(it)?.let { type -> return type } }
        return findMatchingType(outline, binding)
    }

    /** Class whose name is the closest structural match for an aliased import. */
    private fun findMatchingType(outline: PythonFileOutline, binding: PythonImportBinding): PythonTypeDef? {
        val names = listOfNotNull(binding.importedName, binding.localName)
        return outline.types
            .map { type -> type.toPythonBindingScore(names) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /** The best structural score of [names] against a target type name. */
    private fun PythonTypeDef.toPythonBindingScore(names: List<String>): Pair<PythonTypeDef, Int> {
        val score = names.maxOf { PythonBindingMatcher.score(it, name) }
        return this to score
    }



    /**
     * Last resort: the qualifier has no name relation to any import, but exactly one imported file
     * declares a class method (preferred) or module function called [memberName]. Ambiguous matches
     * stay unresolved.
     */
    private fun resolveByImportedDeclaration(
        source: PythonFlowSource,
        qualifier: String,
        memberName: String,
        importedSource: (PythonFlowSource, String) -> PythonFlowSource?
    ): PythonResolvedCall? {
        val candidates = mutableListOf<PythonCallCandidate>()
        for (binding in source.outline.imports.distinctBy { it.specifier }) {
            val targetSource = importedSource(source, binding.specifier) ?: continue
            val target = targetSource.outline
            for (type in target.types) {
                val definition = target.methodIn(type, memberName) ?: continue
                candidates.add(
                    PythonCallCandidate(
                        call = PythonResolvedCall(targetSource, definition),
                        tier = MEMBER_TIER,
                        score = PythonBindingMatcher.score(qualifier, type.name),
                        filePath = targetSource.filePath
                    )
                )
            }
            val definition = target.functionNamed(memberName) ?: continue
            candidates.add(
                PythonCallCandidate(
                    call = PythonResolvedCall(targetSource, definition),
                    tier = MODULE_TIER,
                    score = 0,
                    filePath = targetSource.filePath
                )
            )
        }
        val bestTier = candidates.maxOfOrNull { it.tier } ?: return null
        val inTier = candidates.filter { it.tier == bestTier }
        val bestScore = inTier.maxOf { it.score }
        val best = inTier.filter { it.score == bestScore }
        return if (best.map { it.filePath }.distinct().size == 1) best.first().call else null
    }

    private data class PythonCallCandidate(
        val call: PythonResolvedCall,
        val tier: Int,
        val score: Int,
        val filePath: String
    )

    private companion object {
        const val SELF = "self"
        const val CLS = "cls"
        const val MEMBER_TIER = 2
        const val MODULE_TIER = 1

        /**
         * Qualifiers that never point at an importable declaration, so member calls on them are never
         * resolved through [resolveByImportedDeclaration]. Python builtins and common stdlib modules
         * never contain project declarations.
         */
        val PYTHON_GLOBALS = setOf(
            "print", "len", "range", "enumerate", "zip", "map", "filter", "sorted", "reversed",
            "sum", "min", "max", "abs", "round", "divmod", "pow", "type", "isinstance", "issubclass",
            "id", "hash", "repr", "str", "int", "float", "bool", "list", "dict", "set", "tuple",
            "frozenset", "bytes", "bytearray", "object", "super", "staticmethod", "classmethod",
            "property", "open", "input", "getattr", "setattr", "vars", "dir", "globals", "locals",
            "format", "ord", "chr", "bin", "hex", "oct", "all", "any", "next", "iter", "slice",
            "callable", "hasattr", "exec", "eval", "compile", "Exception", "BaseException",
            "ValueError", "TypeError", "KeyError", "AttributeError", "IndexError", "RuntimeError",
            "NotImplementedError", "os", "sys", "json", "re", "math", "datetime", "time", "random",
            "collections", "string", "pathlib", "functools", "itertools", "unittest", "typing",
            "requests", "flask", "django"
        )
    }
}


// ---------------------------------------------------------------------------------------
// Python binding matcher - pure name matching for import qualifiers
// ---------------------------------------------------------------------------------------

/**
 * Pure name matching used to relate an instance qualifier to an imported binding, mirroring
 * [com.bisotun.language.JsBindingMatcher].
 *
 * A call such as `payment_service.processPayment()` names the *instance* (`payment_service`),
 * while the class is imported as `PaymentService`. Matching is only a filter: the resolver must
 * still find the called member inside the matched target, so a match can never fabricate a
 * declaration.
 */
internal object PythonBindingMatcher {

    /** Shorter names are too ambiguous to be matched structurally (`id`, `db`, `api`). */
    private const val MIN_FUZZY_LENGTH = 4
    private const val EXACT_SCORE = 100
    private const val PLURAL_SCORE = 90
    private const val AFFIX_SCORE = 70
    private const val CONTAINS_SCORE = 50

    /**
     * Relation between [qualifier] and [candidate] in `0..100`; `0` means "unrelated".
     *
     * Case and `_` are ignored, so `payment_service`, `paymentService` and `PaymentService`
     * all compare equal.
     */
    fun score(qualifier: String, candidate: String): Int {
        val left = normalize(qualifier)
        val right = normalize(candidate)
        if (left.isEmpty() || right.isEmpty()) return 0
        if (left == right) return EXACT_SCORE
        if (singular(left) == singular(right)) return PLURAL_SCORE
        if (left.length < MIN_FUZZY_LENGTH || right.length < MIN_FUZZY_LENGTH) return 0
        if (right.endsWith(left) || left.endsWith(right)) return AFFIX_SCORE
        if (right.contains(left) || left.contains(right)) return CONTAINS_SCORE
        return 0
    }

    /** Best score of [qualifier] against the names exposed by [binding]. */
    fun bindingScore(qualifier: String, binding: PythonImportBinding): Int {
        val imported = binding.importedName?.let { score(qualifier, it) } ?: 0
        return maxOf(score(qualifier, binding.localName), imported)
    }

    private fun normalize(value: String): String =
        value.filter { it.isLetterOrDigit() }.lowercase()

    private fun singular(value: String): String =
        if (value.length > MIN_FUZZY_LENGTH && value.endsWith("s")) value.dropLast(1) else value
}


// ---------------------------------------------------------------------------------------
// Python analysis context - per-run cache for parsed Python sources
// ---------------------------------------------------------------------------------------

/**
 * Per-run cache for parsed Python files, used by the tokenizer tier. Nothing is kept between runs.
 *
 * Deliberately platform free: in the IDE the [PythonFlowExtractor] passes `project.basePath` for
 * project-relative paths; the headless [com.bisotun.flowextractor.TextFlowExtractor] passes `null`
 * so absolute fixture paths are preserved.
 */
internal class PythonAnalysisContext(private val basePath: String?) {

    private val sources = HashMap<String, PythonFlowSource?>()

    /** The file currently open in the editor is read through PSI so unsaved edits are respected. */
    fun sourceForFile(file: PsiFile?): PythonFlowSource? {
        if (file == null) return null
        val path = try {
            file.virtualFile?.path
        } catch (t: Throwable) {
            null
        } ?: return null
        val existing = sources[path]
        if (existing != null) return existing
        val text = try {
            file.text
        } catch (t: Throwable) {
            null
        } ?: return null
        val hint = LanguageKindDetector.detect(file)
        val source = buildSource(path, text, hint)
        sources[path] = source
        return source
    }

    /** Reads and parses a file from disk (used when following imports). */
    fun sourceForPath(path: String, hint: LanguageKind): PythonFlowSource? {
        if (sources.containsKey(path)) return sources[path]
        val source = readFromDisk(path, hint)
        sources[path] = source
        return source
    }

    /**
     * Resolves Python import specifiers to an absolute file path (or `null` when nothing exists).
     *
     * Relative specifiers (`./x`, `../x`, plus the native Python `.x` / `..x` forms produced by
     * `from .services import ...`) resolve against the importing file's directory, adding the usual
     * Python extensions and an `__init__.py` fallback.
     *
     * Absolute specifiers (`from service import ...`, `from app.repository import ...`) resolve in
     * two steps, mirroring Python's sys.path behaviour: first against the current file's directory,
     * then against [basePath] when it is non-null (the IDE passes `project.basePath`, so project
     * rooted top-level packages like `app` resolve correctly). Specifiers that map to a known
     * standard/builtin library are left unresolved instead. The priority stays explicit: the current
     * file's directory wins over [basePath], and the first candidate (`.py`, `.pyi`, `.pyw` or
     * `__init__.py` package marker) that exists as a file wins.
     *
     * Everything that can throw (path normalisation, permission errors, non-regular files) is caught
     * and surfaces as `null`, so resolution can never raise.
     */
    fun resolveSpecifier(fromPath: String, specifier: String): String? = safeResolve {
        if (specifier.startsWith(".")) {
            val baseDirectory = File(fromPath).parentFile ?: return@safeResolve null
            // Python `from .services import X` yields a specifier of ".services" (and `from .. import X`
            // yields ".."). The leading dots denote path levels, so ".services" behaves like "./services"
            // and "..services" like "../services". Without this the idiomatic relative import could never
            // be followed because File(dir, ".services") would point at a literal hidden file.
            val dotCount = specifier.takeWhile { it == '.' }.length
            val remainder = specifier.drop(dotCount)
            val parts = buildList {
                repeat(dotCount - 1) { add("..") }
                add(".")
                if (remainder.isNotEmpty()) add(remainder)
            }
            val base = File(baseDirectory, parts.joinToString(File.separator)).toPath().normalize().toString()
            firstExisting(base)
        } else {
            if (specifier.isStdlibModule()) return@safeResolve null
            val dotted = specifier.replace('.', File.separatorChar)
            val fromDirectory = File(fromPath).parentFile ?: return@safeResolve null
            val candidates = buildList {
                add(File(fromDirectory, dotted))
                val basePath = this@PythonAnalysisContext.basePath
                if (basePath != null) add(File(File(basePath), dotted))
            }
            candidates.firstNotNullOfOrNull { candidate -> firstExisting(candidate.toString()) }
        }
    }

    /** `File(candidate)`, `candidate.py`, `candidate.pyi`, `candidate.pyw` or `candidate/__init__.py`. */
    private fun firstExisting(base: String): String? {
        val candidates = mutableListOf(base)
        PYTHON_EXTENSIONS.forEach { candidates.add("$base.$it") }
        candidates.add(File(base, "__init__.py").path)
        return candidates.firstOrNull { path -> File(path).isFile }
    }

    private fun readFromDisk(path: String, hint: LanguageKind): PythonFlowSource? = try {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            null
        } else {
            buildSource(path, file.readText(), hint)
        }
    } catch (t: Throwable) {
        null
    }

    private fun buildSource(path: String, text: String, hint: LanguageKind): PythonFlowSource {
        val byExtension = LanguageKind.fromExtension(File(path).extension)
        val languageKind = if (byExtension == LanguageKind.UNKNOWN) hint else byExtension
        return PythonFlowSource(path, text, PythonFileOutline.parse(text), languageKind)
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        val PYTHON_EXTENSIONS = listOf("py", "pyi", "pyw")

        /** Known stdlib modules that are never project sources. */
        internal val STDLIB_MODULES = setOf(
            "__future__", "__main__", "abc", "argparse", "asyncio", "base64", "bisect", "builtins",
            "collections", "concurrent", "configparser", "contextlib", "copy", "csv", "ctypes",
            "dataclasses", "datetime", "decimal", "enum", "errno", "functools", "glob", "hashlib",
            "heapq", "hmac", "html", "http", "importlib", "inspect", "io", "itertools", "json",
            "keyword", "logging", "math", "multiprocessing", "os", "pathlib", "pickle", "platform",
            "pprint", "queue", "random", "re", "requests", "shutil", "signal", "socket", "sqlite3",
            "ssl", "statistics", "string", "subprocess", "sys", "tempfile", "threading", "time",
            "traceback", "types", "typing", "unittest", "urllib", "uuid", "weakref", "xml"
        )
    }

    private fun String.isStdlibModule(): Boolean = substringBefore('.') in STDLIB_MODULES

    private inline fun safeResolve(block: () -> String?): String? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}

