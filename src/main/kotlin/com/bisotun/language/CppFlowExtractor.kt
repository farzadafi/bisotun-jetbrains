package com.bisotun.language

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import java.io.File

/**
 * C++ strategy and the single home of every C++ extraction concern.
 *
 * IntelliJ IDEA Community (the build target of this plugin) does not bundle the C/C++ PSI plugin:
 * that support ships with CLion (`com.intellij.cidr.lang` / `com.intellij.clion`) and is not part of
 * the Community distribution, nor is it declared as an optional dependency of this plugin. Referencing
 * those PSI classes would therefore break the IC build, and there is no public UAST mapping for C++
 * in the Community platform.
 *
 * Following the exact architecture already used for JavaScript/TypeScript in Community and for
 * Python, C++ is parsed by a bounded, hand written tokenizer plus a conservative declaration outline.
 * No regex is used, no PSI element is fabricated, and every referenced declaration must really exist
 * in the current file or in a project header reachable through a quoted `#include`; everything else
 * is reported as [ResolutionStatus.UNRESOLVED].
 *
 * Supported constructs:
 *  - namespaces (including nested `namespace a::b`), classes/structs, base classes;
 *  - constructors, destructors, methods, free functions, parameters, return types;
 *  - member variables and local variables;
 *  - object creation (`new T(...)` / `T value(...)`) resolved to a real constructor;
 *  - calls (plain, `obj.method()`, `obj->method()`, `NS::Type::method()`);
 *  - control flow: if/else, switch/case/default, for, range-for, while, do-while;
 *  - try/catch, throw, return;
 *  - quoted include dependencies (system `<...>` headers are treated as noise);
 *  - templates (template parameter lists are kept on the declaration) and lambdas.
 *
 * Deliberate limitations (also emitted as a warning in the output):
 *  - declarations are recovered textually, so constructs that are ambiguous without a type checker
 *    (for example `Foo bar();`) stay conservative and are treated as declarations;
 *  - `#define` bodies and system headers are not reported, to avoid macro/library noise;
 *  - control-flow statements are listed for the root scope only, while deeper scopes contribute the
 *    declarations they call (the same shape as the Java crawler output).
 */
class CppFlowExtractor private constructor(
    private val project: Project?,
    private val basePath: String?
) : LanguageFlowExtractor {

    constructor(project: Project) : this(project, project.basePath)

    private val log = Logger.getInstance(CppFlowExtractor::class.java)
    private val callResolver = CppCallResolver()

    override val supportedLanguages: Set<LanguageKind> = setOf(LanguageKind.CPP)

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = LanguageKindDetector.detect(file)
        val caretOffset = caretOffset(file, editor)
        val context = CppAnalysisContext(basePath)
        val source = context.sourceForFile(file)
            ?: return FlowExtractionOutcome.failure(languageKind, "Unable to read this file for tokenizing.")
        val scope = source.outline.scopeAt(caretOffset)
            ?: return FlowExtractionOutcome.failure(languageKind, NO_SCOPE_MESSAGE)

        return try {
            FlowExtractionOutcome(buildFlow(source, scope, maxDepth, includeModels, context), source.languageKind)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            log.warn("C++ flow extraction failed", t)
            FlowExtractionOutcome.failure(
                languageKind,
                "Could not analyze this C++ file: ${t.message ?: "unknown error"}"
            )
        }
    }

    /**
     * Headless entry used by unit tests: the same graph builder as [extractFlow], without PSI.
     * Cancellation still propagates as [ProcessCanceledException].
     */
    internal fun extractFromSource(
        source: CppFlowSource,
        caretOffset: Int,
        maxDepth: Int,
        includeModels: Boolean,
        context: CppAnalysisContext
    ): FlowResult? {
        val scope = source.outline.scopeAt(caretOffset) ?: return null
        return buildFlow(source, scope, maxDepth, includeModels, context)
    }

    private fun caretOffset(file: PsiFile, editor: Editor): Int {
        val textLength = try {
            file.textLength
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            0
        }
        return editor.caretModel.offset.coerceIn(0, maxOf(textLength - 1, 0))
    }

    // ---------------------------------------------------------------------------------------
    // Flow building
    // ---------------------------------------------------------------------------------------

    /** A function together with the file it was parsed from; expansion can cross includes. */
    private data class Handle(val source: CppFlowSource, val definition: CppFunctionDef)

    /** Everything the model section may need: visited functions and explicitly requested types. */
    private class ModelRequest {
        val handles = mutableListOf<Handle>()
        val types = mutableListOf<Pair<CppFlowSource, CppTypeDef>>()
    }

    private fun buildFlow(
        source: CppFlowSource,
        scope: CppScope,
        maxDepth: Int,
        includeModels: Boolean,
        context: CppAnalysisContext
    ): FlowResult {
        val root = rootNode(source, scope)
        val nodes = LinkedHashMap<String, FlowNode>()
        val models = ModelRequest()

        fun add(node: FlowNode) {
            if (nodes.size >= MAX_NODES) return
            if (node.id == root.id) return
            nodes.putIfAbsent(node.id, node)
        }

        when (scope) {
            is CppScope.Function -> {
                val definition = scope.definition
                if (maxDepth > 0) {
                    for (statement in source.outline.controlFlowStatements(definition)) {
                        add(statementNode(source, statement))
                    }
                    for (declaration in source.outline.localDeclarations(definition)) {
                        add(localDeclarationNode(source, declaration))
                    }
                    for (creation in source.outline.objectCreationsIn(definition)) {
                        resolveObjectCreation(source, creation, context)?.let { add(functionNode(it.source, it.definition)) }
                    }
                    expandCalls(source, definition, maxDepth, context, ::add)
                }
                models.handles.add(Handle(source, definition))
            }

            is CppScope.TypeScope -> {
                if (maxDepth > 0) {
                    for (method in source.outline.methodsIn(scope.type)) {
                        add(functionNode(source, method))
                    }
                }
                models.types.add(source to scope.type)
            }

            is CppScope.NamespaceScope -> {
                if (maxDepth > 0) {
                    for (function in source.outline.functionsInNamespace(scope.namespace.qualifiedName)) {
                        add(functionNode(source, function))
                    }
                }
                for (type in source.outline.types) {
                    if (type.namespacePath == scope.namespace.qualifiedName) models.types.add(source to type)
                }
            }

            CppScope.FileScope -> {
                if (maxDepth > 0) {
                    for (include in source.outline.includes) {
                        if (include.isSystem) continue
                        add(includeNode(source, include, context))
                    }
                }
            }
        }

        val modelNodes = if (includeModels) collectModels(models, source, context) else emptyList()
        return FlowResult(root, nodes.values.toList(), modelNodes, listOf(FALLBACK_WARNING))
    }

    /** Breadth first expansion over real call targets; depth is honoured exactly like Java/JS. */
    private fun expandCalls(
        source: CppFlowSource,
        rootDefinition: CppFunctionDef,
        maxDepth: Int,
        context: CppAnalysisContext,
        add: (FlowNode) -> Unit
    ) {
        val rootId = nodeId(source, rootDefinition.startOffset, rootDefinition.endOffset)
        val queued = mutableSetOf<String>()
        var frontier = listOf(Handle(source, rootDefinition))
        var depth = 1
        while (depth <= maxDepth && frontier.isNotEmpty()) {
            val next = mutableListOf<Handle>()
            for (handle in frontier) {
                val search = context.resolutionSources(handle.source)
                for (site in handle.source.outline.callSitesIn(handle.definition)) {
                    val resolved = callResolver.resolve(handle.source, site, search)
                    if (resolved == null) {
                        if (!cppIsUnresolvedNoise(site)) {
                            add(unresolvedNode(handle.source, site))
                        }
                    } else {
                        val node = functionNode(resolved.source, resolved.definition)
                        add(node)
                        if (depth < maxDepth && resolved.definition.hasBody && node.id != rootId && queued.add(node.id)) {
                            next.add(Handle(resolved.source, resolved.definition))
                        }
                    }
                    queueCallbackLambdas(handle, site, depth, maxDepth, rootId, queued, next)
                }
            }
            frontier = next
            depth++
        }
    }

    /**
     * Inline lambdas passed to a call are nested callbacks of that call, not sibling execution
     * steps of the caller. They are expanded at the next depth so predicate/comparator bodies stay
     * attached to the containing call.
     */
    private fun queueCallbackLambdas(
        handle: Handle,
        site: CppCallSite,
        depth: Int,
        maxDepth: Int,
        rootId: String,
        queued: MutableSet<String>,
        next: MutableList<Handle>
    ) {
        if (depth >= maxDepth) return
        for (lambda in handle.source.outline.lambdasInCall(site)) {
            val id = nodeId(handle.source, lambda.startOffset, lambda.endOffset)
            if (id != rootId && queued.add(id)) {
                next.add(Handle(handle.source, lambda))
            }
        }
    }

    private fun resolveObjectCreation(
        source: CppFlowSource,
        creation: CppObjectCreation,
        context: CppAnalysisContext
    ): CppResolvedCall? {
        val search = context.resolutionSources(source)
        val candidates = mutableListOf<CppResolvedCall>()
        for (candidate in search) {
            val type = candidate.outline.typeNamed(creation.typeName) ?: continue
            for (src in search) {
                src.outline.functions
                    .filter { it.kind == CppFunctionKind.CONSTRUCTOR && it.ownerType == type.qualifiedName }
                    .forEach { candidates.add(CppResolvedCall(src, it)) }
            }
            break
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.definition.hasBody } ?: candidates.first()
    }

    // ---------------------------------------------------------------------------------------
    // Node construction
    // ---------------------------------------------------------------------------------------

    private fun rootNode(source: CppFlowSource, scope: CppScope): FlowNode = when (scope) {
        is CppScope.Function -> functionNode(source, scope.definition)
        is CppScope.TypeScope -> plainNode(
            source,
            scope.type.startOffset,
            scope.type.endOffset,
            scope.type.displayName,
            NodeKind.MODEL,
            ResolutionStatus.RESOLVED
        )
        is CppScope.NamespaceScope -> plainNode(
            source,
            scope.namespace.startOffset,
            scope.namespace.endOffset,
            scope.namespace.displayName,
            NodeKind.UNKNOWN,
            ResolutionStatus.RESOLVED
        )
        CppScope.FileScope -> plainNode(
            source,
            0,
            source.text.length,
            "translation unit",
            NodeKind.UNKNOWN,
            ResolutionStatus.RESOLVED
        )
    }

    private fun functionNode(source: CppFlowSource, definition: CppFunctionDef): FlowNode = plainNode(
        source,
        definition.startOffset,
        definition.endOffset,
        definition.displayName,
        definition.nodeKind,
        ResolutionStatus.RESOLVED
    )

    private fun statementNode(source: CppFlowSource, statement: CppStatement): FlowNode = plainNode(
        source,
        statement.startOffset,
        statement.endOffset,
        statement.displayName,
        NodeKind.UNKNOWN,
        ResolutionStatus.RESOLVED
    )

    private fun localDeclarationNode(source: CppFlowSource, declaration: CppLocalDecl): FlowNode = plainNode(
        source,
        declaration.startOffset,
        declaration.endOffset,
        declaration.displayName,
        NodeKind.UNKNOWN,
        ResolutionStatus.RESOLVED
    )

    private fun unresolvedNode(source: CppFlowSource, site: CppCallSite): FlowNode = plainNode(
        source,
        site.offset,
        site.endOffset,
        "${site.rawText}()",
        NodeKind.UNKNOWN,
        ResolutionStatus.UNRESOLVED
    )

    private fun includeNode(source: CppFlowSource, include: CppInclude, context: CppAnalysisContext): FlowNode {
        val resolved = context.resolveInclude(source.filePath, include) != null
        return plainNode(
            source,
            include.startOffset,
            include.endOffset,
            include.displayName,
            NodeKind.UNKNOWN,
            if (resolved) ResolutionStatus.RESOLVED else ResolutionStatus.UNRESOLVED
        )
    }

    private fun plainNode(
        source: CppFlowSource,
        startOffset: Int,
        endOffset: Int,
        displayName: String,
        kind: NodeKind,
        status: ResolutionStatus
    ): FlowNode {
        val start = startOffset.coerceIn(0, source.text.length)
        val end = endOffset.coerceIn(start, source.text.length)
        return FlowNode(
            id = nodeId(source, start, end),
            displayName = displayName,
            filePath = jsRelativePath(source.filePath, basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = status,
            kind = kind,
            code = source.text.substring(start, end).trim()
        )
    }

    private fun nodeId(source: CppFlowSource, startOffset: Int, endOffset: Int): String =
        "${source.filePath}:$startOffset-$endOffset"

    // ---------------------------------------------------------------------------------------
    // Models
    // ---------------------------------------------------------------------------------------

    /**
     * Models are only the class/struct declarations that are actually parsed in the current file or
     * in a project header it includes. Derived classes contribute their base classes, and classes
     * contribute the types of their fields, so inheritance and composition stay visible.
     */
    private fun collectModels(
        request: ModelRequest,
        rootSource: CppFlowSource,
        context: CppAnalysisContext
    ): List<FlowNode> {
        val searchSources = context.resolutionSources(rootSource)
        val names = LinkedHashSet<String>()

        for (handle in request.handles) {
            val definition = handle.definition
            definition.returnType?.let { rawType ->
                cppSimpleTypeName(rawType)?.let { typeName -> names.add(typeName) }
            }
            definition.parameters.forEach { if (it.typeName.isNotEmpty()) names.add(it.typeName) }
            handle.source.outline.typeEnvironment(definition).values.forEach { names.add(it) }
            handle.source.outline.objectCreationsIn(definition).forEach { names.add(it.typeName) }
            for (site in handle.source.outline.callSitesIn(definition)) {
                if (site.separatorsOnly("::")) {
                    site.segments.dropLast(1).forEach { names.add(it) }
                }
            }
            definition.ownerType?.let { names.add(it) }
        }

        val found = LinkedHashMap<String, FlowNode>()
        val queue = ArrayDeque<Pair<CppFlowSource, CppTypeDef>>()

        fun enqueue(match: Pair<CppFlowSource, CppTypeDef>) {
            if (found.putIfAbsent(match.second.qualifiedName, modelNode(match.first, match.second)) == null) {
                queue.addLast(match)
            }
        }

        for ((source, type) in request.types) enqueue(source to type)
        for (name in names) {
            findType(searchSources, name)?.let { enqueue(it) }
        }

        while (queue.isNotEmpty() && found.size < MAX_MODELS) {
            val type = queue.removeFirst().second
            for (base in type.bases) {
                findType(searchSources, base)?.let { enqueue(it) }
            }
            for (field in type.fields) {
                if (field.typeName.isEmpty()) continue
                findType(searchSources, field.typeName)?.let { enqueue(it) }
            }
        }
        return found.values.toList()
    }

    private fun findType(sources: List<CppFlowSource>, name: String): Pair<CppFlowSource, CppTypeDef>? {
        val effective = cppEffectiveTypeName(name) ?: cppSimpleTypeName(name) ?: name.substringAfterLast("::")
        for (source in sources) {
            val exact = source.outline.types.firstOrNull { it.qualifiedName == name || it.qualifiedName == effective }
            if (exact != null) return source to exact
        }
        for (source in sources) {
            val byName = source.outline.types.firstOrNull { it.name == effective }
            if (byName != null) return source to byName
        }
        return null
    }

    private fun modelNode(source: CppFlowSource, type: CppTypeDef): FlowNode = plainNode(
        source,
        type.startOffset,
        type.endOffset,
        type.qualifiedName,
        NodeKind.MODEL,
        ResolutionStatus.RESOLVED
    )

    companion object {
        private const val NO_SCOPE_MESSAGE =
            "Please place the caret inside a C++ function, method, class or namespace."

        private const val FALLBACK_WARNING =
            "⚠ C++ extraction uses a bounded tokenizer because the IntelliJ C/C++ PSI plugin (CLion) " +
                "is not available in this IDE. Calls that cannot be resolved to a parsed declaration " +
                "are marked as unresolved, and system headers/macros are not reported."

        private const val MAX_NODES = 300
        private const val MAX_MODELS = 40

        internal fun forAnalysis(basePath: String?): CppFlowExtractor = CppFlowExtractor(null, basePath)
    }
}

/** Extracts the head type name from a raw C++ type (`const std::vector<int>&` -> `vector`). */
internal fun cppSimpleTypeName(raw: String): String? {
    val cleaned = raw.replace("*", " ").replace("&", " ")
    val withoutTemplate = cleaned.substringBefore("<")
    return withoutTemplate.split(Regex("[^A-Za-z0-9_:]+"))
        .filter { it.isNotBlank() }
        .lastOrNull {
            it !in setOf(
                "const", "volatile", "struct", "class", "typename", "unsigned", "signed", "long", "short"
            )
        }
        ?.substringAfterLast("::")
        ?.takeIf { it.isNotBlank() }
}

/**
 * Receiver type used for member lookup. Smart pointers and `optional` unwrap to their inner type
 * (`std::unique_ptr<Worker>` -> `Worker`); every other template keeps its primary type
 * (`Repository<Job>` -> `Repository`).
 */
internal fun cppEffectiveTypeName(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val simple = cppSimpleTypeName(raw) ?: return null
    if (simple in CPP_INDIRECTION_TYPES) {
        val inner = cppFirstTemplateArgument(raw) ?: return simple
        return cppSimpleTypeName(inner) ?: inner.substringAfterLast("::").takeIf { it.isNotBlank() }
    }
    return simple
}

internal fun cppFirstTemplateArgument(raw: String): String? {
    val start = raw.indexOf('<')
    if (start < 0) return null
    var depth = 0
    for (index in start until raw.length) {
        when (raw[index]) {
            '<' -> depth++
            '>' -> {
                depth--
                if (depth == 0) return raw.substring(start + 1, index).trim().takeIf { it.isNotEmpty() }
            }
        }
    }
    return null
}

/**
 * True when [owner] and [expected] name the same class. Unqualified names match a unique qualified
 * owner; two different namespaces never match (`core::Pipeline` vs `other::Pipeline`).
 */
internal fun cppOwnersCompatible(owner: String?, expected: String?): Boolean {
    if (owner.isNullOrBlank() || expected.isNullOrBlank()) return false
    if (owner == expected) return true
    val ownerSimple = owner.substringAfterLast("::")
    val expectedSimple = expected.substringAfterLast("::")
    if (ownerSimple != expectedSimple) return false
    val ownerNs = owner.substringBeforeLast("::", "")
    val expectedNs = expected.substringBeforeLast("::", "")
    return ownerNs.isNotEmpty() && expectedNs.isNotEmpty() && ownerNs == expectedNs
}

/** Unresolved calls that are standard-library noise and must not appear as flow steps. */
internal fun cppIsUnresolvedNoise(site: CppCallSite): Boolean {
    if (site.name in CPP_ACCESSOR_NOISE_NAMES) return true
    if (site.segments.any { it in CppFileOutline.NOISE_QUALIFIERS }) return true
    return false
}

internal val CPP_INDIRECTION_TYPES = setOf(
    "unique_ptr", "shared_ptr", "weak_ptr", "auto_ptr", "optional", "reference_wrapper"
)

/**
 * Low-value accessors and container/exception methods. Project declarations with these names are
 * still emitted when they resolve; unresolved uses are filtered.
 */
internal val CPP_ACCESSOR_NOISE_NAMES = setOf(
    "size", "empty", "push_back", "emplace_back", "pop_back", "insert", "erase", "clear", "reserve",
    "resize", "data", "c_str", "substr", "append", "find", "count", "front", "back", "get", "reset",
    "begin", "end", "at", "length", "capacity", "swap", "sort", "what", "code", "message"
)

// ---------------------------------------------------------------------------------------
// Language kinds and parsed source
// ---------------------------------------------------------------------------------------

/** One expandable scope of the C++ strategy. */
internal sealed interface CppScope {
    data class Function(val definition: CppFunctionDef) : CppScope
    data class TypeScope(val type: CppTypeDef) : CppScope
    data class NamespaceScope(val namespace: CppNamespace) : CppScope
    data object FileScope : CppScope
}

/** A parsed C++ file, as the tokenizer tier sees it (platform free). */
internal data class CppFlowSource(
    val filePath: String,
    val text: String,
    val outline: CppFileOutline,
    val languageKind: LanguageKind
)

// ---------------------------------------------------------------------------------------
// Tokenizer
// ---------------------------------------------------------------------------------------

internal enum class CppTokenType { IDENTIFIER, KEYWORD, NUMBER, STRING, CHAR, PUNCTUATION, PREPROCESSOR }

internal data class CppToken(
    val type: CppTokenType,
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * Hand written C++ tokenizer.
 *
 * State machine that understands line/block comments, quoted strings and character literals,
 * prefixed and raw string literals, digit separators, and whole preprocessor directives (with
 * backslash continuations) so that no declaration or call is ever detected inside a comment, a
 * string or a macro body. It is deliberately not a regex scanner.
 */
internal class CppTokenizer(private val text: String) {

    private val tokens = ArrayList<CppToken>()
    private var index = 0

    fun tokenize(): List<CppToken> {
        while (index < text.length) {
            val ch = text[index]
            when {
                ch.isWhitespace() -> index++
                ch == '/' && peek(1) == '/' -> skipLineComment()
                ch == '/' && peek(1) == '*' -> skipBlockComment()
                ch == '#' && onlyWhitespaceBeforeOnLine() -> readPreprocessor()
                ch == '"' -> readQuoted('"', CppTokenType.STRING)
                ch == '\'' -> readQuoted('\'', CppTokenType.CHAR)
                ch.isDigit() -> readNumber()
                isIdentifierStart(ch) -> readIdentifier()
                else -> readPunctuation()
            }
        }
        return tokens
    }

    private fun peek(offset: Int): Char? = text.getOrNull(index + offset)

    private fun skipLineComment() {
        while (index < text.length && text[index] != '\n') index++
    }

    private fun skipBlockComment() {
        index += 2
        while (index < text.length) {
            if (text[index] == '*' && peek(1) == '/') {
                index += 2
                return
            }
            index++
        }
    }

    private fun onlyWhitespaceBeforeOnLine(): Boolean {
        var cursor = index - 1
        while (cursor >= 0 && text[cursor] != '\n') {
            if (!text[cursor].isWhitespace()) return false
            cursor--
        }
        return true
    }

    private fun readPreprocessor() {
        val start = index
        index++
        while (index < text.length) {
            val ch = text[index]
            if (ch == '\\' && peek(1) == '\n') {
                index += 2
                continue
            }
            if (ch == '\\' && peek(1) == '\r') {
                index += 2
                if (peek(0) == '\n') index++
                continue
            }
            if (ch == '\n') break
            index++
        }
        val value = text.substring(start, index).trimEnd()
        if (value.isNotEmpty()) tokens.add(CppToken(CppTokenType.PREPROCESSOR, value, start, index))
    }

    private fun readQuoted(quote: Char, type: CppTokenType) {
        val start = index
        index++
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index = (index + 2).coerceAtMost(text.length)
                quote -> {
                    index++
                    break
                }
                '\n' -> break
                else -> index++
            }
        }
        addToken(type, start)
    }

    private fun readIdentifier() {
        val start = index
        while (index < text.length && isIdentifierPart(text[index])) index++
        val value = text.substring(start, index)
        val prefixedString = value in STRING_PREFIXES && peek(0) == '"'
        if (prefixedString) {
            if (value.endsWith("R")) {
                readRawString(start, value.dropLast(1))
            } else {
                readQuoted('"', CppTokenType.STRING)
            }
            return
        }
        val type = if (value in KEYWORDS) CppTokenType.KEYWORD else CppTokenType.IDENTIFIER
        tokens.add(CppToken(type, value, start, index))
    }

    /** `R"delim( ... )delim"`; the delimiter is preserved as part of the token text. */
    private fun readRawString(start: Int, prefix: String) {
        index++ // opening quote
        val delimiterStart = index
        while (index < text.length && text[index] != '(' && index - delimiterStart <= 16) index++
        if (index >= text.length || text[index] != '(') {
            // Not a well formed raw string: fall back to a normal quoted string so the rest of the
            // file is still tokenized sensibly.
            readQuoted('"', CppTokenType.STRING)
            return
        }
        val delimiter = text.substring(delimiterStart, index)
        val terminator = ")$delimiter\""
        val end = text.indexOf(terminator, index)
        index = if (end < 0) text.length else end + terminator.length
        val value = prefix + "\"" + delimiter + "(" + text.substring(delimiterStart + delimiter.length + 1, index)
        tokens.add(CppToken(CppTokenType.STRING, value, start, index))
    }

    private fun readNumber() {
        val start = index
        var previous = '\u0000'
        while (index < text.length) {
            val ch = text[index]
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_') {
                previous = ch
                index++
                continue
            }
            // C++14 digit separator: `1'000'000` (never a character literal).
            if (ch == '\'' && text.getOrNull(index + 1)?.isLetterOrDigit() == true && previous.isLetterOrDigit()) {
                previous = ch
                index++
                continue
            }
            break
        }
        addToken(CppTokenType.NUMBER, start)
    }

    private fun readPunctuation() {
        val start = index
        val length = when {
            matches(THREE_CHAR_OPERATORS, 3) -> 3
            matches(TWO_CHAR_OPERATORS, 2) -> 2
            else -> 1
        }
        index = (index + length).coerceAtMost(text.length)
        addToken(CppTokenType.PUNCTUATION, start)
    }

    private fun matches(operators: Set<String>, length: Int): Boolean {
        if (index + length > text.length) return false
        return text.substring(index, index + length) in operators
    }

    private fun addToken(type: CppTokenType, start: Int) {
        if (index <= start) return
        tokens.add(CppToken(type, text.substring(start, index), start, index))
    }

    private companion object {
        val KEYWORDS = setOf(
            "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool", "break",
            "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl", "concept",
            "const", "consteval", "constexpr", "constinit", "const_cast", "continue", "co_await",
            "co_return", "co_yield", "decltype", "default", "delete", "do", "double", "dynamic_cast",
            "else", "enum", "explicit", "export", "extern", "false", "float", "for", "friend", "goto",
            "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept", "not", "not_eq",
            "nullptr", "operator", "or", "or_eq", "private", "protected", "public", "register",
            "reinterpret_cast", "requires", "return", "short", "signed", "sizeof", "static",
            "static_assert", "static_cast", "struct", "switch", "template", "this", "thread_local",
            "throw", "true", "try", "typedef", "typeid", "typename", "union", "unsigned", "using",
            "virtual", "void", "volatile", "wchar_t", "while", "xor", "xor_eq", "override", "final"
        )

        val STRING_PREFIXES = setOf("L", "u", "U", "u8", "R", "LR", "uR", "UR", "u8R")

        val THREE_CHAR_OPERATORS = setOf("<<=", ">>=", "->*", "...", "<=>")

        val TWO_CHAR_OPERATORS = setOf(
            "::", "->", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=",
            "&=", "|=", "^=", "<<", ">>", ".*", "##"
        )

        fun isIdentifierStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

        fun isIdentifierPart(ch: Char): Boolean = ch == '_' || ch.isLetterOrDigit()
    }
}

// ---------------------------------------------------------------------------------------
// Outline model
// ---------------------------------------------------------------------------------------

internal enum class CppFunctionKind {
    FUNCTION, METHOD, CONSTRUCTOR, DESTRUCTOR, LAMBDA;

    val nodeKind: NodeKind
        get() = when (this) {
            FUNCTION, LAMBDA -> NodeKind.FUNCTION
            METHOD, CONSTRUCTOR, DESTRUCTOR -> NodeKind.METHOD
        }
}

internal data class CppParam(val name: String, val typeName: String)

internal data class CppFieldDef(val name: String, val typeName: String)

/**
 * A function, method, constructor, destructor or lambda that was really parsed.
 *
 * [startOffset] begins at the template prefix (when present), [endOffset] ends at the closing brace
 * of the body or at the terminating `;` of a declaration. [bodyOpenToken] is `-1` for declaration-only
 * entities such as header prototypes.
 */
internal data class CppFunctionDef(
    val name: String,
    val qualifiedName: String,
    val displayName: String,
    val startOffset: Int,
    val endOffset: Int,
    val nameOffset: Int,
    val paramsOpenToken: Int,
    val paramsCloseToken: Int,
    val bodyOpenToken: Int,
    val bodyCloseToken: Int,
    val kind: CppFunctionKind,
    val namespacePath: String,
    val ownerType: String?,
    val templateParams: List<String>,
    val returnType: String?,
    val parameters: List<CppParam>
) {
    val hasBody: Boolean get() = bodyOpenToken >= 0 && bodyCloseToken > bodyOpenToken

    val nodeKind: NodeKind get() = kind.nodeKind
}

internal data class CppTypeDef(
    val name: String,
    val qualifiedName: String,
    val displayName: String,
    val namespacePath: String,
    val keyword: String,
    val startOffset: Int,
    val endOffset: Int,
    val bodyOpenToken: Int,
    val bodyCloseToken: Int,
    val bases: List<String>,
    val fields: List<CppFieldDef>,
    val templateParams: List<String>
) {
    val hasBody: Boolean get() = bodyOpenToken >= 0 && bodyCloseToken > bodyOpenToken
}

internal data class CppNamespace(
    val qualifiedName: String,
    val startOffset: Int,
    val endOffset: Int,
    val bodyOpenToken: Int,
    val bodyCloseToken: Int,
    val isAnonymous: Boolean
) {
    val displayName: String get() = if (isAnonymous) "namespace {anonymous}" else "namespace $qualifiedName"
}

internal data class CppInclude(
    val raw: String,
    val target: String,
    val isSystem: Boolean,
    val startOffset: Int,
    val endOffset: Int
) {
    val displayName: String get() = if (isSystem) "#include <$target>" else "#include \"$target\""
}

/** A control-flow statement of the root scope; only its header is kept to avoid giant snippets. */
internal data class CppStatement(
    val displayName: String,
    val startOffset: Int,
    val endOffset: Int
)

/** A local variable / field-style declaration inside a function body. */
internal data class CppLocalDecl(
    val name: String,
    val typeName: String,
    val startOffset: Int,
    val endOffset: Int,
    val displayName: String
)

/** One call site. [segments] keeps the whole chain (`engine.turbo.start` -> [engine, turbo, start]). */
internal data class CppCallSite(
    val name: String,
    val segments: List<String>,
    val separators: List<String>,
    val offset: Int,
    val endOffset: Int,
    val rawText: String,
    val argumentCount: Int
) {
    fun separatorsOnly(separator: String): Boolean =
        separators.isNotEmpty() && separators.all { it == separator }
}

/** An object creation (`new T(...)`) that can be mapped onto a real constructor. */
internal data class CppObjectCreation(
    val typeName: String,
    val segments: List<String>,
    val offset: Int,
    val endOffset: Int,
    val rawText: String
)

internal data class CppResolvedCall(val source: CppFlowSource, val definition: CppFunctionDef)

// ---------------------------------------------------------------------------------------
// Parsed view of a C++ file
// ---------------------------------------------------------------------------------------

internal class CppFileOutline(
    val text: String,
    val functions: List<CppFunctionDef>,
    val types: List<CppTypeDef>,
    val namespaces: List<CppNamespace>,
    val includes: List<CppInclude>,
    val lambdas: List<CppFunctionDef>,
    internal val tokens: List<CppToken>,
    internal val bracketMatches: IntArray
) {

    fun hasDeclarations(): Boolean =
        functions.isNotEmpty() || types.isNotEmpty() || includes.isNotEmpty() || lambdas.isNotEmpty()

    /** Innermost expandable declaration containing [offset]; the caller validates the caret. */
    fun scopeAt(offset: Int): CppScope? {
        functionContaining(offset)?.let { return CppScope.Function(it) }
        val type = types
            .filter { offset >= it.startOffset && offset <= it.endOffset }
            .minByOrNull { it.endOffset - it.startOffset }
        if (type != null && type.hasBody) return CppScope.TypeScope(type)
        val namespace = namespaces
            .filter { offset >= it.startOffset && offset <= it.endOffset }
            .maxByOrNull { it.startOffset }
        if (namespace != null) return CppScope.NamespaceScope(namespace)
        return if (hasDeclarations()) CppScope.FileScope else null
    }

    fun functionContaining(offset: Int): CppFunctionDef? =
        (functions + lambdas)
            .filter { offset >= it.startOffset && offset <= it.endOffset }
            .maxByOrNull { it.startOffset }

    fun functionNamed(name: String): CppFunctionDef? =
        functions.firstOrNull { it.name == name || it.qualifiedName == name }

    fun functionsInNamespace(namespacePath: String): List<CppFunctionDef> =
        functions.filter { it.namespacePath == namespacePath && it.ownerType == null }

    fun typeNamed(name: String): CppTypeDef? {
        val simple = name.substringAfterLast("::")
        types.firstOrNull { it.qualifiedName == name }?.let { return it }
        types.firstOrNull { it.name == simple }?.let { return it }
        return types.firstOrNull { it.qualifiedName.endsWith("::$simple") }
    }

    fun typesNamed(name: String): List<CppTypeDef> {
        val simple = name.substringAfterLast("::")
        return types.filter { it.qualifiedName == name || it.name == simple }
    }

    fun methodsIn(type: CppTypeDef): List<CppFunctionDef> = functions.filter { function ->
        function.ownerType != null && function.ownerType == type.qualifiedName
    }

    fun methodIn(type: CppTypeDef, name: String): CppFunctionDef? =
        methodsIn(type).firstOrNull { it.name == name }

    fun constructorOf(type: CppTypeDef): CppFunctionDef? =
        methodsIn(type).firstOrNull { it.kind == CppFunctionKind.CONSTRUCTOR }

    fun fieldNamed(type: CppTypeDef, name: String): CppFieldDef? = type.fields.firstOrNull { it.name == name }

    /** Parses one parameter (`const std::string& name = "x"`), returning null for unnamed `void`. */
    fun parseParameter(from: Int, to: Int): CppParam? {
        val parsed = parseTypeAndName(from, to) ?: return null
        if (parsed.third < to) {
            val after = tokens[parsed.third].text
            if (after != "=" && after != "," && after != "[") return null
        }
        return CppParam(parsed.second, parsed.first)
    }

    fun enclosingTypeAt(offset: Int): CppTypeDef? = types
        .filter { offset >= it.startOffset && offset <= it.endOffset }
        .minByOrNull { it.endOffset - it.startOffset }

    /** Owner type of the innermost method-like declaration containing [offset] (also across lambdas). */
    fun enclosingMethodTypeAt(offset: Int): String? = functions
        .filter { it.ownerType != null && offset >= it.startOffset && offset <= it.endOffset }
        .minByOrNull { it.endOffset - it.startOffset }
        ?.ownerType

    fun enclosingNamespaceAt(offset: Int): CppNamespace? = namespaces
        .filter { offset >= it.startOffset && offset <= it.endOffset }
        .maxByOrNull { it.startOffset }

    // -----------------------------------------------------------------------------------
    // Statements and declarations
    // -----------------------------------------------------------------------------------

    fun controlFlowStatements(function: CppFunctionDef): List<CppStatement> {
        if (!function.hasBody) return emptyList()
        val limit = function.bodyCloseToken
        val nested = nestedCallables(function)
        val statements = LinkedHashMap<Int, CppStatement>()
        var i = function.bodyOpenToken + 1
        while (i < limit && i < tokens.size && statements.size < MAX_STATEMENTS) {
            if (isInsideNested(nested, tokens[i].startOffset)) {
                i++
                continue
            }
            val statement = when (tokens[i].text) {
                "if", "while", "switch", "catch" -> headerStatement(i, limit)
                "for" -> headerStatement(i, limit)
                "case" -> caseLabel(i, limit)
                "default" -> defaultLabel(i)
                "else" -> elseStatement(i, limit)
                "do" -> CppStatement("do", tokens[i].startOffset, tokens[i].endOffset)
                "try" -> CppStatement("try", tokens[i].startOffset, tokens[i].endOffset)
                "throw" -> toSemicolon(i, limit)
                "return" -> toSemicolon(i, limit)
                else -> null
            }
            if (statement != null) statements.putIfAbsent(statement.startOffset, statement)
            i++
        }
        return statements.values.toList()
    }

    private fun headerStatement(keywordIndex: Int, limit: Int): CppStatement? {
        val open = findNext(keywordIndex + 1, limit, "(")
        if (open < 0) return null
        val close = bracketMatches[open]
        if (close <= open || close >= limit) return null
        val inner = collapse(text.substring(tokens[open].endOffset, tokens[close].startOffset))
        return CppStatement(
            displayName = "${tokens[keywordIndex].text} ($inner)",
            startOffset = tokens[keywordIndex].startOffset,
            endOffset = tokens[close].endOffset
        )
    }

    private fun elseStatement(index: Int, limit: Int): CppStatement {
        if (tokens.getOrNull(index + 1)?.text == "if") {
            headerStatement(index + 1, limit)?.let { nested ->
                return CppStatement("else ${nested.displayName}", tokens[index].startOffset, nested.endOffset)
            }
        }
        return CppStatement("else", tokens[index].startOffset, tokens[index].endOffset)
    }

    private fun caseLabel(index: Int, limit: Int): CppStatement? {
        var cursor = index + 1
        var guard = 0
        while (cursor < limit && cursor < tokens.size && guard++ < 200) {
            if (tokens[cursor].text == ":") {
                val value = collapse(text.substring(tokens[index].endOffset, tokens[cursor].startOffset))
                return CppStatement("case $value:", tokens[index].startOffset, tokens[cursor].endOffset)
            }
            if (tokens[cursor].text == ";" || tokens[cursor].text == "}") return null
            cursor++
        }
        return null
    }

    private fun defaultLabel(index: Int): CppStatement? {
        val colon = tokens.getOrNull(index + 1) ?: return null
        if (colon.text != ":") return null
        return CppStatement("default:", tokens[index].startOffset, colon.endOffset)
    }

    private fun toSemicolon(index: Int, limit: Int): CppStatement {
        var cursor = index + 1
        var guard = 0
        while (cursor < limit && cursor < tokens.size && guard++ < 400) {
            val text = tokens[cursor].text
            if (text == ";") {
                val value = collapse(this.text.substring(tokens[index].endOffset, tokens[cursor].startOffset))
                val keyword = tokens[index].text
                val display = if (value.isEmpty()) keyword else "$keyword $value"
                return CppStatement(display, tokens[index].startOffset, tokens[cursor].endOffset)
            }
            if (text == "}" || text == "{") break
            cursor++
        }
        return CppStatement(tokens[index].text, tokens[index].startOffset, tokens[index].endOffset)
    }

    fun localDeclarations(function: CppFunctionDef): List<CppLocalDecl> {
        if (!function.hasBody) return emptyList()
        val declarations = LinkedHashMap<Int, CppLocalDecl>()
        val limit = function.bodyCloseToken
        val nested = nestedCallables(function)
        var statementStart = function.bodyOpenToken + 1
        var parenDepth = 0
        var i = statementStart
        while (i <= limit && i < tokens.size) {
            if (parenDepth == 0 && isInsideNested(nested, tokens[i].startOffset)) {
                i++
                continue
            }
            when (tokens[i].text) {
                "(", "[" -> parenDepth++
                ")", "]" -> if (parenDepth > 0) parenDepth--
                ";", "{", "}" -> if (parenDepth == 0) {
                    if (i > statementStart) {
                        parseLocalDeclaration(statementStart, i)?.let { declarations.putIfAbsent(it.startOffset, it) }
                    }
                    statementStart = i + 1
                }
            }
            i++
        }
        return declarations.values.toList()
    }

    private fun parseLocalDeclaration(from: Int, to: Int): CppLocalDecl? {
        val parsed = parseTypeAndName(from, to) ?: return null
        val afterNameIndex = parsed.third
        if (afterNameIndex < to) {
            val after = tokens[afterNameIndex].text
            if (after !in LOCAL_DECLARATION_FOLLOWERS) return null
        }
        val start = tokens[from].startOffset
        val end = tokens[to - 1].endOffset
        return CppLocalDecl(
            name = parsed.second,
            typeName = parsed.first,
            startOffset = start,
            endOffset = end,
            displayName = collapse(text.substring(start, end))
        )
    }

    fun typeEnvironment(function: CppFunctionDef): Map<String, String> {
        val environment = LinkedHashMap<String, String>()
        for (parameter in function.parameters) {
            if (parameter.name.isNotEmpty() && parameter.typeName.isNotEmpty()) {
                environment[parameter.name] = parameter.typeName
            }
        }
        for (declaration in localDeclarations(function)) {
            if (declaration.typeName.isNotEmpty() && declaration.typeName != "auto") {
                environment[declaration.name] = declaration.typeName
            }
        }
        return environment
    }

    fun objectCreationsIn(function: CppFunctionDef): List<CppObjectCreation> {
        if (!function.hasBody) return emptyList()
        val limit = function.bodyCloseToken
        val nested = nestedCallables(function)
        val creations = LinkedHashMap<Int, CppObjectCreation>()
        var i = function.bodyOpenToken + 1
        while (i < limit && i < tokens.size) {
            if (isInsideNested(nested, tokens[i].startOffset)) {
                i++
                continue
            }
            if (tokens[i].text != "new") {
                i++
                continue
            }
            var cursor = i + 1
            val segments = mutableListOf<String>()
            if (tokens.getOrNull(cursor)?.type == CppTokenType.IDENTIFIER) {
                segments.add(tokens[cursor].text)
                cursor++
                while (cursor < limit) {
                    if (tokens[cursor].text == "<") {
                        val angle = findAngleClose(cursor, limit)
                        if (angle <= cursor) break
                        cursor = angle + 1
                        continue
                    }
                    if (tokens[cursor].text == "::" && tokens.getOrNull(cursor + 1)?.type == CppTokenType.IDENTIFIER) {
                        segments.add(tokens[cursor + 1].text)
                        cursor += 2
                        continue
                    }
                    break
                }
            }
            if (segments.isEmpty()) {
                i++
                continue
            }
            val open = cursor
            if (tokens.getOrNull(open)?.text != "(") {
                i++
                continue
            }
            val close = bracketMatches[open]
            if (close <= open) {
                i++
                continue
            }
            creations.putIfAbsent(
                tokens[i].startOffset,
                CppObjectCreation(
                    typeName = segments.last(),
                    segments = segments.toList(),
                    offset = tokens[i].startOffset,
                    endOffset = tokens[close].endOffset,
                    rawText = "new ${segments.joinToString("::")}"
                )
            )
            i = close + 1
        }
        return creations.values.toList()
    }

    // -----------------------------------------------------------------------------------
    // Call sites
    // -----------------------------------------------------------------------------------

    fun callSitesIn(function: CppFunctionDef): List<CppCallSite> {
        if (!function.hasBody) return emptyList()
        val declarationOffsets = (functions + lambdas).mapTo(HashSet()) { it.nameOffset }
        val nested = nestedCallables(function)
        val sites = LinkedHashMap<Int, CppCallSite>()
        val limit = function.bodyCloseToken
        var i = function.bodyOpenToken + 1
        while (i < limit && i < tokens.size) {
            val token = tokens[i]
            if (isInsideNested(nested, token.startOffset)) {
                i++
                continue
            }
            if (token.type != CppTokenType.IDENTIFIER && token.text != "this") {
                i++
                continue
            }
            val previous = tokens.getOrNull(i - 1)
            if (previous != null && previous.text in setOf(".", "->", "::", "~")) {
                i++
                continue
            }
            if (previous != null && previous.text in DECLARATION_LEADERS) {
                i++
                continue
            }

            val segments = mutableListOf(token.text)
            val separators = mutableListOf<String>()
            var last = i
            while (true) {
                var next = last + 1
                if (tokens.getOrNull(next)?.text == "<") {
                    val angle = findAngleClose(next, limit)
                    if (angle <= next) break
                    next = angle + 1
                }
                val separator = tokens.getOrNull(next)?.text
                val nameToken = tokens.getOrNull(next + 1)
                if (separator != null && separator in CHAIN_SEPARATORS && nameToken?.type == CppTokenType.IDENTIFIER) {
                    segments.add(nameToken.text)
                    separators.add(separator)
                    last = next + 1
                    continue
                }
                break
            }

            val open = last + 1
            if (tokens.getOrNull(open)?.text != "(") {
                i = last + 1
                continue
            }
            val close = bracketMatches[open]
            if (close <= open) {
                i = last + 1
                continue
            }
            if (token.startOffset in declarationOffsets) {
                i = last + 1
                continue
            }
            if (previous != null && (previous.type == CppTokenType.IDENTIFIER ||
                    previous.text == ">" || previous.text == "*" || previous.text == "&")
            ) {
                // `Type name(...)` is a declaration or an object creation, handled elsewhere.
                i = last + 1
                continue
            }
            if (isNoiseCall(segments)) {
                i = last + 1
                continue
            }
            sites.putIfAbsent(
                token.startOffset,
                CppCallSite(
                    name = segments.last(),
                    segments = segments.toList(),
                    separators = separators.toList(),
                    offset = token.startOffset,
                    endOffset = tokens[close].endOffset,
                    rawText = segments.zipWithNextToString(separators),
                    argumentCount = countArguments(open, close)
                )
            )
            i = last + 1
        }
        return sites.values.toList()
    }

    /** Lambdas whose source range sits inside the argument list of [site]. */
    fun lambdasInCall(site: CppCallSite): List<CppFunctionDef> =
        lambdas.filter { it.startOffset > site.offset && it.endOffset <= site.endOffset }

    /**
     * Nested lambdas (and rare local callables) whose bodies belong to [function] but must not be
     * treated as top-level statements or call sites of that function.
     */
    fun nestedCallables(function: CppFunctionDef): List<CppFunctionDef> {
        if (!function.hasBody) return emptyList()
        val bodyStart = tokens.getOrNull(function.bodyOpenToken)?.startOffset ?: return emptyList()
        val bodyEnd = function.endOffset
        return (lambdas + functions).filter { nested ->
            nested.startOffset != function.startOffset &&
                nested.startOffset >= bodyStart &&
                nested.endOffset <= bodyEnd
        }
    }

    private fun isInsideNested(nested: List<CppFunctionDef>, offset: Int): Boolean =
        nested.any { offset >= it.startOffset && offset <= it.endOffset }

    fun countArguments(open: Int, close: Int): Int {
        if (close <= open + 1) return 0
        var depth = 0
        var angle = 0
        var commas = 0
        var hasToken = false
        for (index in open + 1 until close) {
            when (tokens[index].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> if (depth > 0) depth--
                "<" -> angle++
                ">" -> if (angle > 0) angle--
                ">>" -> angle = maxOf(0, angle - 2)
                "," -> if (depth == 0 && angle == 0) commas++
                else -> hasToken = true
            }
        }
        return if (!hasToken && commas == 0) 0 else commas + 1
    }

    private fun isNoiseCall(segments: List<String>): Boolean {
        val name = segments.last()
        if (name.isEmpty()) return true
        if (name in ALWAYS_NOISE_CALL_NAMES) return true
        if (segments.any { it in NOISE_QUALIFIERS }) return true
        if (isMacroName(name)) return true
        return false
    }

    private fun isMacroName(name: String): Boolean =
        name.length > 1 && name.all { it.isUpperCase() || it.isDigit() || it == '_' }

    // -----------------------------------------------------------------------------------
    // Low level helpers
    // -----------------------------------------------------------------------------------

    fun findAngleClose(openIndex: Int, limit: Int): Int {
        var depth = 0
        var i = openIndex
        var guard = 0
        while (i < limit && i < tokens.size && guard++ < 400) {
            when (tokens[i].text) {
                "<" -> depth++
                ">" -> {
                    depth--
                    if (depth == 0) return i
                }
                ">>" -> {
                    depth -= 2
                    if (depth <= 0) return i
                }
                ";", "{", "}", "(" -> if (depth == 0) return -1
            }
            i++
        }
        return -1
    }

    private fun findNext(from: Int, to: Int, text: String): Int {
        var i = from
        while (i < to && i < tokens.size) {
            if (tokens[i].text == text) return i
            i++
        }
        return -1
    }

    /**
     * Parses `[modifiers] Type[*&&] name` inside `[from, to)` and returns `(typeName, name, indexAfterName)`.
     * Used for parameters, local declarations, fields and class members.
     */
    private fun parseTypeAndName(from: Int, to: Int): Triple<String, String, Int>? {
        var i = from
        while (i < to && tokens[i].text in DECLARATION_MODIFIERS) i++
        if (i >= to) return null
        val head = tokens[i]
        val isTypeHead = head.type == CppTokenType.IDENTIFIER ||
            head.text in BUILTIN_TYPE_KEYWORDS ||
            head.text == "auto" || head.text == "decltype"
        if (!isTypeHead) return null
        var typeName = head.text
        i++
        if (head.text == "decltype") {
            if (tokens.getOrNull(i)?.text == "(") {
                val close = bracketMatches[i]
                if (close <= i) return null
                i = close + 1
            }
        }
        while (i < to) {
            if (tokens[i].text == "::" && tokens.getOrNull(i + 1)?.type == CppTokenType.IDENTIFIER &&
                tokens.getOrNull(i + 1)?.text !in DECLARATION_MODIFIERS
            ) {
                typeName = tokens[i + 1].text
                i += 2
                continue
            }
            if (tokens[i].text == "<") {
                val angle = findAngleClose(i, to)
                if (angle <= i) return null
                val template = text.substring(tokens[i].startOffset, tokens[angle].endOffset)
                    .replace(Regex("\\s+"), "")
                if (!typeName.endsWith(">")) typeName += template
                i = angle + 1
                continue
            }
            break
        }
        while (i < to && tokens[i].text in setOf("*", "&", "&&", "const", "volatile")) i++
        if (i >= to) return null
        if (tokens[i].type != CppTokenType.IDENTIFIER) return null
        val name = tokens[i].text
        return Triple(typeName, name, i + 1)
    }

    fun parseField(from: Int, to: Int): CppFieldDef? {
        val parsed = parseTypeAndName(from, to) ?: return null
        if (parsed.third < to) {
            val after = tokens[parsed.third].text
            if (after !in FIELD_FOLLOWERS) return null
        }
        return CppFieldDef(parsed.second, parsed.first)
    }

    private fun collapse(value: String): String {
        val collapsed = value.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length <= MAX_DISPLAY_LENGTH) collapsed else collapsed.take(MAX_DISPLAY_LENGTH - 1) + "…"
    }

    private fun List<String>.zipWithNextToString(separators: List<String>): String {
        if (isEmpty()) return ""
        val result = StringBuilder(this[0])
        for (index in 1 until size) {
            result.append(separators.getOrElse(index - 1) { "." }).append(this[index])
        }
        return result.toString()
    }

    companion object {
        private const val MAX_STATEMENTS = 120
        private const val MAX_DISPLAY_LENGTH = 120

        val ACCESS_SPECIFIERS = setOf("public", "private", "protected")
        val CHAIN_SEPARATORS = setOf(".", "->", "::")
        val DECLARATION_MODIFIERS = setOf(
            "static", "constexpr", "constinit", "const", "volatile", "inline", "explicit", "friend",
            "virtual", "typename", "struct", "class", "enum", "register", "thread_local", "mutable",
            "unsigned", "signed"
        )
        val BUILTIN_TYPE_KEYWORDS = setOf(
            "void", "bool", "char", "char8_t", "char16_t", "char32_t", "wchar_t", "short", "int",
            "long", "float", "double", "signed", "unsigned"
        )
        val LOCAL_DECLARATION_FOLLOWERS = setOf("=", ";", "{", ",", "(", "[")
        val FIELD_FOLLOWERS = setOf("=", ";", "{", ",", "[", ":")
        val DECLARATION_LEADERS = setOf(
            "new", "delete", "sizeof", "alignof", "decltype", "static_cast", "dynamic_cast",
            "const_cast", "reinterpret_cast", "typeid", "using", "typedef", "namespace",
            "template", "operator"
        )

        val NOISE_QUALIFIERS = setOf(
            "std", "cout", "cin", "cerr", "clog", "endl", "printf", "fprintf", "sprintf", "snprintf",
            "malloc", "calloc", "realloc", "free", "memcpy", "memmove", "memset", "assert"
        )

        val ALWAYS_NOISE_CALL_NAMES = setOf(
            "sizeof", "alignof", "decltype", "typeid", "static_cast", "dynamic_cast", "const_cast",
            "reinterpret_cast", "printf", "fprintf", "sprintf", "snprintf", "malloc", "calloc",
            "realloc", "free", "memcpy", "memmove", "memset", "strlen", "strcmp", "strcpy", "strcat",
            "assert", "abort", "exit", "atoi", "atof", "atol", "rand", "srand", "time", "clock",
            "getenv", "to_string", "stoi", "stol", "stod", "make_shared", "make_unique", "make_pair",
            "move", "forward"
        )

        fun parse(text: String): CppFileOutline = CppFileOutlineBuilder(text).build()
    }
}

// ---------------------------------------------------------------------------------------
// Outline builder
// ---------------------------------------------------------------------------------------

/**
 * Builds a [CppFileOutline] by walking the token stream. Declarations that cannot be recovered
 * unambiguously are skipped rather than approximated, and every produced range maps to real text.
 */
internal class CppFileOutlineBuilder(private val text: String) {

    private val tokens = CppTokenizer(text).tokenize()
    private val bracketMatches = IntArray(tokens.size) { -1 }
    private val functions = mutableListOf<CppFunctionDef>()
    private val types = mutableListOf<CppTypeDef>()
    private val namespaces = mutableListOf<CppNamespace>()
    private val includes = mutableListOf<CppInclude>()
    private val lambdas = mutableListOf<CppFunctionDef>()
    private val knownTypeNames = mutableSetOf<String>()

    fun build(): CppFileOutline {
        matchBrackets()
        collectIncludes()
        prescanTypeNames()
        parseDeclarations(0, tokens.size, "", null, emptyList(), -1)
        collectLambdas()
        return CppFileOutline(
            text = text,
            functions = functions.distinctBy { it.startOffset to it.endOffset }.sortedBy { it.startOffset },
            types = types.distinctBy { it.startOffset to it.endOffset }.sortedBy { it.startOffset },
            namespaces = namespaces.distinctBy { it.startOffset }.sortedBy { it.startOffset },
            includes = includes,
            lambdas = lambdas.distinctBy { it.startOffset to it.endOffset }.sortedBy { it.startOffset },
            tokens = tokens,
            bracketMatches = bracketMatches
        )
    }

    private fun matchBrackets() {
        val stack = ArrayDeque<Int>()
        for (index in tokens.indices) {
            when (tokens[index].text) {
                "(", "[", "{" -> stack.addLast(index)
                ")", "]", "}" -> {
                    val expectedOpen = when (tokens[index].text) {
                        ")" -> "("
                        "]" -> "["
                        else -> "{"
                    }
                    val temporary = ArrayList<Int>()
                    var open = -1
                    while (stack.isNotEmpty()) {
                        val candidate = stack.removeLast()
                        if (tokens[candidate].text == expectedOpen) {
                            open = candidate
                            break
                        }
                        temporary.add(candidate)
                    }
                    for (pending in temporary.asReversed()) stack.addLast(pending)
                    if (open >= 0) {
                        bracketMatches[open] = index
                        bracketMatches[index] = open
                    }
                }
            }
        }
    }

    private fun collectIncludes() {
        for (token in tokens) {
            if (token.type != CppTokenType.PREPROCESSOR) continue
            val body = token.text.removePrefix("#").trim()
            if (!body.startsWith("include")) continue
            val rest = body.removePrefix("include").trim()
            when {
                rest.startsWith("<") && rest.contains(">") -> includes.add(
                    CppInclude(
                        token.text,
                        rest.substringAfter("<").substringBefore(">").trim(),
                        isSystem = true,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset
                    )
                )
                rest.startsWith("\"") && rest.indexOf('"', 1) > 0 -> includes.add(
                    CppInclude(
                        token.text,
                        rest.substring(1, rest.indexOf('"', 1)).trim(),
                        isSystem = false,
                        startOffset = token.startOffset,
                        endOffset = token.endOffset
                    )
                )
            }
        }
    }

    /** Forward declarations and definitions may appear after a use, so names are collected first. */
    private fun prescanTypeNames() {
        for (index in tokens.indices) {
            if (tokens[index].text != "class" && tokens[index].text != "struct") continue
            val name = tokens.getOrNull(index + 1) ?: continue
            if (name.type == CppTokenType.IDENTIFIER) knownTypeNames.add(name.text)
        }
    }

    private fun parseDeclarations(
        from: Int,
        to: Int,
        namespacePath: String,
        ownerType: CppTypeDef?,
        templateParams: List<String>,
        templateStartOffset: Int
    ) {
        var i = from
        var pendingTemplate = templateParams
        var pendingTemplateStart = templateStartOffset
        var guard = 0
        while (i < to && i < tokens.size && guard++ < MAX_DECLARATIONS) {
            val token = tokens[i]
            when {
                token.type == CppTokenType.PREPROCESSOR -> i++
                token.text == "namespace" -> {
                    i = handleNamespace(i, to, namespacePath, ownerType)
                    pendingTemplate = emptyList()
                }
                token.text == "template" -> {
                    val parsed = parseTemplateParameters(i, to)
                    if (parsed == null) {
                        i++
                    } else {
                        pendingTemplate = parsed.first
                        pendingTemplateStart = token.startOffset
                        i = parsed.second
                    }
                }
                token.text == "class" || token.text == "struct" -> {
                    i = handleType(i, to, namespacePath, ownerType, pendingTemplate, pendingTemplateStart)
                    pendingTemplate = emptyList()
                    pendingTemplateStart = -1
                }
                token.text == "enum" -> i = skipToStatementEnd(i, to)
                token.text == "extern" && tokens.getOrNull(i + 1)?.type == CppTokenType.STRING -> {
                    val brace = findNext(i + 2, to, "{")
                    val close = if (brace >= 0) bracketMatches[brace] else -1
                    if (brace >= 0 && close > brace) {
                        parseDeclarations(brace + 1, close, namespacePath, ownerType, emptyList(), -1)
                        i = close + 1
                    } else {
                        i++
                    }
                }
                token.text in ACCESS_SPECIFIERS && tokens.getOrNull(i + 1)?.text == ":" -> i += 2
                token.text == "using" || token.text == "typedef" ||
                    token.text == "static_assert" || token.text == "friend" -> i = skipToStatementEnd(i, to)
                else -> {
                    i = handleDeclaration(i, to, namespacePath, ownerType, pendingTemplate, pendingTemplateStart)
                    pendingTemplate = emptyList()
                    pendingTemplateStart = -1
                }
            }
        }
    }

    private fun skipToStatementEnd(from: Int, to: Int): Int {
        var i = from
        var guard = 0
        while (i < to && i < tokens.size && guard++ < MAX_DECLARATIONS) {
            when (tokens[i].text) {
                "{" -> {
                    val close = bracketMatches[i]
                    if (close <= i) return to
                    i = close + 1
                }
                ";" -> return i + 1
                "}" -> return i + 1
                else -> i++
            }
        }
        return to
    }

    private fun handleNamespace(
        index: Int,
        to: Int,
        namespacePath: String,
        ownerType: CppTypeDef?
    ): Int {
        var cursor = index + 1
        if (tokens.getOrNull(cursor)?.text == "inline") cursor++
        val names = mutableListOf<String>()
        if (tokens.getOrNull(cursor)?.type == CppTokenType.IDENTIFIER) {
            names.add(tokens[cursor].text)
            cursor++
            while (tokens.getOrNull(cursor)?.text == "::" &&
                tokens.getOrNull(cursor + 1)?.type == CppTokenType.IDENTIFIER
            ) {
                names.add(tokens[cursor + 1].text)
                cursor += 2
            }
        }
        if (tokens.getOrNull(cursor)?.text == "=") return skipToStatementEnd(cursor, to)
        val brace = findNext(cursor, to, "{")
        if (brace < 0 || brace >= to) return maxOf(cursor, index + 1)
        val close = bracketMatches[brace]
        if (close <= brace) return brace + 1
        val path = listOf(namespacePath, names.joinToString("::"))
            .filter { it.isNotEmpty() }
            .joinToString("::")
        namespaces.add(
            CppNamespace(
                qualifiedName = path,
                startOffset = tokens[index].startOffset,
                endOffset = tokens[close].endOffset,
                bodyOpenToken = brace,
                bodyCloseToken = close,
                isAnonymous = names.isEmpty()
            )
        )
        parseDeclarations(brace + 1, close, path, ownerType, emptyList(), -1)
        return close + 1
    }

    private fun parseTemplateParameters(index: Int, to: Int): Pair<List<String>, Int>? {
        val open = findNext(index + 1, to, "<")
        if (open < 0) return null
        val close = outline().findAngleClose(open, to)
        if (close <= open) return null
        val parameters = splitTopLevel(open + 1, close).mapNotNull { (start, end) ->
            if (start >= end) null else text.substring(tokens[start].startOffset, tokens[end - 1].endOffset)
                .replace(Regex("\\s+"), " ")
                .trim()
                .takeIf { it.isNotEmpty() }
        }
        return parameters to (close + 1)
    }

    private fun handleType(
        index: Int,
        to: Int,
        namespacePath: String,
        ownerType: CppTypeDef?,
        templateParams: List<String>,
        templateStartOffset: Int
    ): Int {
        val keyword = tokens[index].text
        val end = findDeclarationEnd(index, to)
        if (end < 0 || end >= to) return index + 1
        val colon = findTopLevelColon(index, end)
        val nameStop = if (colon >= 0) colon else end
        val nameIndex = lastIdentifier(index + 1, nameStop)
        if (nameIndex < 0) {
            return if (tokens[end].text == "{") skipBlockEnd(end) else end + 1
        }
        val name = tokens[nameIndex].text
        val qualifiedName = listOf(namespacePath, ownerType?.name ?: "", name)
            .filter { it.isNotEmpty() }
            .joinToString("::")
        val bases = if (colon >= 0) parseBases(colon + 1, end) else emptyList()
        val bodyOpen = if (tokens[end].text == "{") end else -1
        val bodyClose = if (bodyOpen >= 0) bracketMatches[bodyOpen] else -1
        val startOffset = if (templateStartOffset >= 0) templateStartOffset else tokens[index].startOffset
        val identifiersBeforeHeader = (index + 1 until nameStop).filter {
            tokens[it].type == CppTokenType.IDENTIFIER && tokens[it].text !in CppFileOutline.DECLARATION_MODIFIERS
        }
        if (tokens[end].text == ";" && colon < 0 && identifiersBeforeHeader.size > 1) {
            // `struct Foo value;` uses an elaborated type specifier; it does not declare a type here.
            return end + 1
        }
        val endOffset = if (bodyClose > bodyOpen) tokens[bodyClose].endOffset else tokens[end].endOffset
        val type = CppTypeDef(
            name = name,
            qualifiedName = qualifiedName,
            displayName = "$keyword $qualifiedName",
            namespacePath = namespacePath,
            keyword = keyword,
            startOffset = startOffset,
            endOffset = endOffset,
            bodyOpenToken = bodyOpen,
            bodyCloseToken = bodyClose,
            bases = bases,
            fields = emptyList(),
            templateParams = templateParams
        )
        types.add(type)
        knownTypeNames.add(name)
        if (bodyClose > bodyOpen) {
            parseDeclarations(bodyOpen + 1, bodyClose, namespacePath, type, emptyList(), -1)
            return bodyClose + 1
        }
        return end + 1
    }

    private fun handleDeclaration(
        from: Int,
        to: Int,
        namespacePath: String,
        ownerType: CppTypeDef?,
        templateParams: List<String>,
        templateStartOffset: Int
    ): Int {
        val end = findDeclarationEnd(from, to)
        if (end < 0 || end >= to || end >= tokens.size) return from + 1
        val endToken = tokens[end]
        if (endToken.text == "}" || endToken.text == ";") {
            // Malformed or expression statement: never guess a declaration out of it.
            if (end == from) return from + 1
        }
        val signature = parseFunctionSignature(
            from = from,
            to = end,
            namespacePath = namespacePath,
            ownerType = ownerType,
            templateParams = templateParams,
            templateStartOffset = templateStartOffset,
            declarationOnly = endToken.text == ";"
        )
        if (signature != null) {
            val bodyOpen = if (endToken.text == "{") end else -1
            val bodyClose = if (bodyOpen >= 0) bracketMatches[bodyOpen] else -1
            if (bodyOpen >= 0 && bodyClose <= bodyOpen) return from + 1
            val endOffset = if (bodyClose > bodyOpen) tokens[bodyClose].endOffset else endToken.endOffset
            functions.add(
                signature.copy(
                    endOffset = endOffset,
                    bodyOpenToken = bodyOpen,
                    bodyCloseToken = bodyClose
                )
            )
            return if (bodyClose > bodyOpen) bodyClose + 1 else end + 1
        }

        if (ownerType != null && endToken.text == ";") {
            addField(ownerType, from, end)
        }
        return if (endToken.text == "{" && bracketMatches[end] > end) bracketMatches[end] + 1 else end + 1
    }

    private fun addField(ownerType: CppTypeDef, from: Int, to: Int) {
        val field = CppFileOutline(text, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), tokens, bracketMatches)
            .parseField(from, to) ?: return
        val typeIndex = types.indexOfFirst { it.startOffset == ownerType.startOffset }
        if (typeIndex < 0) return
        val existing = types[typeIndex]
        if (existing.fields.any { it.name == field.name }) return
        types[typeIndex] = existing.copy(fields = existing.fields + field)
    }

    private fun parseFunctionSignature(
        from: Int,
        to: Int,
        namespacePath: String,
        ownerType: CppTypeDef?,
        templateParams: List<String>,
        templateStartOffset: Int,
        declarationOnly: Boolean
    ): CppFunctionDef? {
        if (from >= to) return null
        var start = from
        while (start < to && tokens[start].text == "[" ) {
            val close = bracketMatches[start]
            if (close in (start + 1) until to) start = close + 1 else break
        }
        if (start >= to) return null
        if (tokens[start].text in NON_FUNCTION_STARTERS) return null
        if (tokens[start].type == CppTokenType.KEYWORD && tokens[start].text !in CppFileOutline.BUILTIN_TYPE_KEYWORDS &&
            tokens[start].text != "auto" && tokens[start].text != "const"
        ) {
            val allowedModifier = tokens[start].text in setOf(
                "static", "inline", "extern", "explicit", "virtual", "friend", "constexpr", "consteval"
            )
            if (!allowedModifier) return null
        }

        val open = findParameterListOpen(start, to) ?: return null
        val close = bracketMatches[open]
        if (close <= open || close >= to) return null

        // `int x = factory(1);` reaches here with the factory call as first parens; assignments are
        // never function declarations.
        var cursor = start
        while (cursor < open) {
            if (tokens[cursor].text == "=") return null
            cursor++
        }

        var nameIndex = open - 1
        if (tokens.getOrNull(nameIndex)?.text == ">") {
            val angleOpen = findAngleOpenBackwards(nameIndex, start)
            if (angleOpen < 0) return null
            nameIndex = angleOpen - 1
        }
        if (nameIndex < start) return null
        val nameToken = tokens[nameIndex]
        if (nameToken.type != CppTokenType.IDENTIFIER) return null
        if (nameToken.text in NON_FUNCTION_STARTERS) return null

        val isDestructor = nameIndex - 1 >= start && tokens[nameIndex - 1].text == "~"
        var chainStart = if (isDestructor) nameIndex - 1 else nameIndex
        val qualifiers = mutableListOf<String>()
        while (chainStart - 2 >= start &&
            tokens[chainStart - 1].text == "::" &&
            tokens[chainStart - 2].type == CppTokenType.IDENTIFIER
        ) {
            qualifiers.add(0, tokens[chainStart - 2].text)
            chainStart -= 2
        }

        val simpleName = if (isDestructor) "~${nameToken.text}" else nameToken.text
        val ownerSimpleName = ownerType?.name
        val isConstructor = !isDestructor &&
            (qualifiers.lastOrNull() == simpleName || (ownerSimpleName != null && ownerSimpleName == simpleName))
        val kind = when {
            isDestructor -> CppFunctionKind.DESTRUCTOR
            isConstructor -> CppFunctionKind.CONSTRUCTOR
            qualifiers.isNotEmpty() || ownerType != null -> CppFunctionKind.METHOD
            else -> CppFunctionKind.FUNCTION
        }

        val returnType = if (kind == CppFunctionKind.CONSTRUCTOR || kind == CppFunctionKind.DESTRUCTOR) {
            null
        } else {
            typeText(chainStart, start)
        }
        val returnSimple = returnType?.let { cppSimpleTypeName(it) }

        if (kind == CppFunctionKind.FUNCTION && returnType.isNullOrBlank()) return null

        // `Shape value(1, 2);` / `Engine value(7);` is a variable whose initializer uses a
        // constructor, not a function declaration. Only a single bare type token qualifies: a
        // storage modifier (`static Engine create(int);`) or any other type shape is a declaration.
        val singleBareType = chainStart - start == 1 && tokens[start].type == CppTokenType.IDENTIFIER
        val looksLikeObjectDeclaration = declarationOnly && qualifiers.isEmpty() && singleBareType &&
            returnSimple != null && returnSimple in knownTypeNames && simpleName !in knownTypeNames
        if (looksLikeObjectDeclaration) return null

        val parameterText = text.substring(tokens[open].endOffset, tokens[close].startOffset)
            .replace(Regex("\\s+"), " ")
            .trim()
        val prefix = when {
            qualifiers.isNotEmpty() -> {
                val joined = qualifiers.joinToString("::")
                if (namespacePath.isNotEmpty() && !joined.startsWith("$namespacePath::") && joined != namespacePath) {
                    "$namespacePath::$joined"
                } else {
                    joined
                }
            }
            ownerType != null -> ownerType.qualifiedName
            else -> namespacePath
        }
        val qualifiedName = listOf(prefix, simpleName).filter { it.isNotEmpty() }.joinToString("::")
        val templatePrefix = if (templateParams.isNotEmpty()) {
            "template<${templateParams.joinToString(", ")}> "
        } else {
            ""
        }
        val signature = when {
            kind == CppFunctionKind.CONSTRUCTOR || kind == CppFunctionKind.DESTRUCTOR ->
                "$qualifiedName($parameterText)"
            returnType.isNullOrBlank() -> "$qualifiedName($parameterText)"
            else -> "$returnType $qualifiedName($parameterText)"
        }
        val nameOffset = tokens[nameIndex].startOffset
        return CppFunctionDef(
            name = simpleName,
            qualifiedName = qualifiedName,
            displayName = templatePrefix + signature,
            startOffset = if (templateStartOffset >= 0) templateStartOffset else tokens[start].startOffset,
            endOffset = tokens[close].endOffset,
            nameOffset = nameOffset,
            paramsOpenToken = open,
            paramsCloseToken = close,
            bodyOpenToken = -1,
            bodyCloseToken = -1,
            kind = kind,
            namespacePath = namespacePath,
            ownerType = when (kind) {
                CppFunctionKind.FUNCTION, CppFunctionKind.LAMBDA -> null
                else -> prefix
            },
            templateParams = templateParams,
            returnType = returnType,
            parameters = parseParameters(open, close)
        )
    }

    private fun findParameterListOpen(from: Int, to: Int): Int? {
        var i = from
        var guard = 0
        while (i < to && guard++ < 400) {
            if (tokens[i].text == "(") return i
            if (tokens[i].text == ";" || tokens[i].text == "{") return null
            i++
        }
        return null
    }

    private fun findAngleOpenBackwards(closeIndex: Int, limit: Int): Int {
        var depth = 0
        var i = closeIndex
        var guard = 0
        while (i >= limit && guard++ < 200) {
            when (tokens[i].text) {
                ">" -> depth++
                ">>" -> depth += 2
                "<" -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i--
        }
        return -1
    }

    private fun typeText(typeEnd: Int, signatureStart: Int): String {
        if (typeEnd <= signatureStart) return ""
        val start = tokens[signatureStart].startOffset
        val endOffset = tokens[typeEnd - 1].endOffset
        if (endOffset <= start) return ""
        return text.substring(start, endOffset).replace(Regex("\\s+"), " ").trim()
    }

    private fun parseParameters(open: Int, close: Int): List<CppParam> {
        val parameters = mutableListOf<CppParam>()
        for ((start, end) in splitTopLevel(open + 1, close)) {
            if (start >= end) continue
            if (tokens[start].text == "void" && end == start + 1) continue
            val parsed = outline().parseParameter(start, end) ?: continue
            parameters.add(parsed)
        }
        return parameters
    }

    private fun parseBases(from: Int, to: Int): List<String> {
        val bases = mutableListOf<String>()
        for ((start, end) in splitTopLevel(from, to)) {
            var cursor = start
            // `public virtual core::Base` -> access specifiers and `virtual` are not part of the name.
            while (cursor < end && (tokens[cursor].text in ACCESS_SPECIFIERS || tokens[cursor].text == "virtual")) {
                cursor++
            }
            var name: String? = null
            while (cursor < end) {
                if (tokens[cursor].type == CppTokenType.IDENTIFIER) {
                    name = tokens[cursor].text
                    cursor++
                    while (cursor + 1 < end && tokens[cursor].text == "::" &&
                        tokens[cursor + 1].type == CppTokenType.IDENTIFIER
                    ) {
                        name = tokens[cursor + 1].text
                        cursor += 2
                    }
                    break
                }
                cursor++
            }
            if (name != null && name !in bases) bases.add(name)
        }
        return bases
    }

    private fun collectLambdas() {
        for (index in tokens.indices) {
            if (tokens[index].text != "[") continue
            if (tokens.getOrNull(index + 1)?.text == "[") continue
            val previous = tokens.getOrNull(index - 1)
            if (previous != null && (previous.type == CppTokenType.IDENTIFIER ||
                    previous.text == ")" || previous.text == "]" || previous.text == ">")
            ) {
                continue
            }
            val captureClose = bracketMatches[index]
            if (captureClose <= index) continue

            var cursor = captureClose + 1
            var paramsOpen = -1
            var paramsClose = -1
            if (tokens.getOrNull(cursor)?.text == "(") {
                paramsOpen = cursor
                paramsClose = bracketMatches[cursor]
                if (paramsClose <= cursor) continue
                cursor = paramsClose + 1
            }
            while (tokens.getOrNull(cursor)?.text in LAMBDA_SPECIFIERS) cursor++
            if (tokens.getOrNull(cursor)?.text == "->") {
                cursor++
                var guard = 0
                while (cursor < tokens.size && tokens[cursor].text != "{" && guard++ < 30) cursor++
            }
            if (tokens.getOrNull(cursor)?.text != "{") continue
            val bodyClose = bracketMatches[cursor]
            if (bodyClose <= cursor) continue

            val parameterText = if (paramsOpen >= 0 && paramsClose > paramsOpen) {
                text.substring(tokens[paramsOpen].endOffset, tokens[paramsClose].startOffset)
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } else {
                ""
            }
            val boundName = if (previous != null && previous.text == "=") {
                tokens.getOrNull(index - 2)?.takeIf { it.type == CppTokenType.IDENTIFIER }?.text
            } else {
                null
            }
            val name = boundName ?: "lambda"
            val startOffset = tokens[index].startOffset
            val ownerType = lambdasOwnerType(startOffset)
            val namespace = namespaces
                .filter { startOffset >= it.startOffset && startOffset <= it.endOffset }
                .maxByOrNull { it.startOffset }
                ?.qualifiedName ?: ""
            lambdas.add(
                CppFunctionDef(
                    name = name,
                    qualifiedName = listOf(namespace, ownerType ?: "", name)
                        .filter { it.isNotEmpty() }.joinToString("::"),
                    displayName = "$name($parameterText)",
                    startOffset = startOffset,
                    endOffset = tokens[bodyClose].endOffset,
                    nameOffset = startOffset,
                    paramsOpenToken = paramsOpen,
                    paramsCloseToken = paramsClose,
                    bodyOpenToken = cursor,
                    bodyCloseToken = bodyClose,
                    kind = CppFunctionKind.LAMBDA,
                    namespacePath = namespace,
                    ownerType = ownerType,
                    templateParams = emptyList(),
                    returnType = null,
                    parameters = if (paramsOpen >= 0) parseParameters(paramsOpen, paramsClose) else emptyList()
                )
            )
        }
    }

    private fun lambdasOwnerType(offset: Int): String? = types
        .filter { offset >= it.startOffset && offset <= it.endOffset }
        .minByOrNull { it.endOffset - it.startOffset }
        ?.qualifiedName

    private fun findDeclarationEnd(from: Int, to: Int): Int {
        var i = from
        var depth = 0
        var guard = 0
        while (i < to && i < tokens.size && guard++ < MAX_DECLARATIONS) {
            when (tokens[i].text) {
                "(", "[" -> depth++
                ")", "]" -> if (depth > 0) depth--
                "{", ";" -> if (depth == 0) return i
                "}" -> if (depth == 0) return i
            }
            i++
        }
        return to
    }

    private fun findTopLevelColon(from: Int, to: Int): Int {
        var i = from
        var depth = 0
        while (i < to && i < tokens.size) {
            when (tokens[i].text) {
                "(", "[", "{" -> depth++
                ")", "]", "}" -> if (depth > 0) depth--
                ":" -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun lastIdentifier(from: Int, to: Int): Int {
        var index = to - 1
        while (index >= from) {
            val token = tokens[index]
            if (token.type == CppTokenType.IDENTIFIER && token.text !in setOf("final", "override")) return index
            index--
        }
        return -1
    }

    private fun splitTopLevel(from: Int, to: Int): List<Pair<Int, Int>> {
        val segments = mutableListOf<Pair<Int, Int>>()
        var start = from
        var depth = 0
        var i = from
        while (i < to && i < tokens.size) {
            when (tokens[i].text) {
                "(", "[", "<" -> depth++
                ")", "]", ">" -> if (depth > 0) depth--
                ">>" -> depth = maxOf(0, depth - 2)
                "," -> if (depth == 0) {
                    segments.add(start to i)
                    start = i + 1
                }
            }
            i++
        }
        segments.add(start to to)
        return segments.filter { it.first < it.second }
    }

    private fun findNext(from: Int, to: Int, text: String): Int {
        var i = from
        while (i < to && i < tokens.size) {
            if (tokens[i].text == text) return i
            i++
        }
        return -1
    }

    private fun skipBlockEnd(brace: Int): Int {
        val close = bracketMatches[brace]
        return if (close > brace) close + 1 else brace + 1
    }

    private fun outline(): CppFileOutline =
        CppFileOutline(text, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), tokens, bracketMatches)

    private companion object {
        const val MAX_DECLARATIONS = 20000

        val ACCESS_SPECIFIERS = setOf("public", "private", "protected")

        val LAMBDA_SPECIFIERS = setOf("mutable", "constexpr", "consteval", "noexcept", "static", "inline")

        val NON_FUNCTION_STARTERS = setOf(
            "return", "if", "else", "for", "while", "do", "switch", "case", "break", "continue", "goto",
            "throw", "try", "catch", "delete", "new", "using", "typedef", "namespace", "class", "struct",
            "enum", "friend", "static_assert", "template", "public", "private", "protected", "sizeof",
            "decltype", "static_cast", "dynamic_cast", "const_cast", "reinterpret_cast", "co_return",
            "co_await", "co_yield", "this", "operator", "default"
        )
    }
}

// ---------------------------------------------------------------------------------------
// Call resolution
// ---------------------------------------------------------------------------------------

/**
 * Conservative resolution for C++ call sites.
 *
 * A call is only expanded when it really points at a parsed declaration:
 *  1. `obj.method()` / `obj->method()`: the qualifier's type is taken from parameters, locals, or
 *     fields of the enclosing class (looked up in the current file, quoted includes, and the
 *     companion translation unit);
 *  2. `NS::Type::method()`: the qualified owner is looked up across those same sources;
 *  3. plain `name()`: enclosing class method (including out-of-line definitions later in the file),
 *     then namespace function, then a unique same-file free function.
 *
 * Overloads are accepted only when arity matches uniquely, or when the type declares a single
 * method of that name. Anything else stays unresolved; no declaration is ever invented.
 */
internal class CppCallResolver {

    fun resolve(
        source: CppFlowSource,
        site: CppCallSite,
        searchSources: List<CppFlowSource>
    ): CppResolvedCall? {
        val sources = uniqueSources(source, searchSources)
        val separators = site.separators
        if (separators.isEmpty()) return resolvePlain(source, site, sources)

        val qualifiers = site.segments.dropLast(1).filter { it != "this" }
        val lastSeparator = separators.last()
        if (lastSeparator == "." || lastSeparator == "->") {
            if (qualifiers.isEmpty()) {
                return pickMember(sources, enclosingOwner(source, site.offset), site.name, site.argumentCount)
            }
            val qualifier = qualifiers.last()
            val typeName = localTypeOf(source, site.offset, qualifier)
                ?: fieldTypeOf(source, sources, site.offset, qualifier)
            if (typeName != null) {
                pickMember(sources, typeName, site.name, site.argumentCount)?.let { return it }
                return null
            }
            if (uniqueTypeNamed(sources, qualifier) != null) {
                return pickMember(sources, qualifier, site.name, site.argumentCount)
            }
            return null
        }

        if (lastSeparator == "::") {
            val owner = qualifiers.joinToString("::")
            pickMember(sources, owner, site.name, site.argumentCount)?.let { return it }
            pickNamespaceFunction(sources, owner, site.name, site.argumentCount)?.let { return it }
            if (qualifiers.size > 1) {
                pickMember(sources, qualifiers.last(), site.name, site.argumentCount)?.let { return it }
            }
            return null
        }
        return null
    }

    private fun resolvePlain(
        source: CppFlowSource,
        site: CppCallSite,
        sources: List<CppFlowSource>
    ): CppResolvedCall? {
        val enclosingTypeName = enclosingOwner(source, site.offset)
        pickMember(sources, enclosingTypeName, site.name, site.argumentCount)?.let { return it }
        if (!enclosingTypeName.isNullOrBlank()) {
            val namespace = enclosingTypeName.substringBeforeLast("::", "")
            pickNamespaceFunction(sources, namespace, site.name, site.argumentCount)?.let { return it }
        }
        val boundLambdas = source.outline.lambdas.filter { it.name == site.name && it.name != "lambda" }
        if (boundLambdas.size == 1) return CppResolvedCall(source, boundLambdas.single())

        val namespace = source.outline.enclosingNamespaceAt(site.offset)?.qualifiedName
        if (!namespace.isNullOrEmpty()) {
            pickNamespaceFunction(sources, namespace, site.name, site.argumentCount)?.let { return it }
        }
        val sameFile = source.outline.functions
            .filter { it.name == site.name && it.ownerType == null }
            .map { CppResolvedCall(source, it) }
        return pickBest(sameFile, site.argumentCount)
    }

    private fun pickMember(
        sources: List<CppFlowSource>,
        ownerName: String?,
        memberName: String,
        argCount: Int
    ): CppResolvedCall? {
        if (ownerName.isNullOrBlank()) return null
        val type = uniqueTypeNamed(sources, ownerName)
        val expectedOwner = type?.second?.qualifiedName ?: ownerName
        val candidates = mutableListOf<CppResolvedCall>()
        for (src in sources) {
            for (function in src.outline.functions) {
                if (function.name != memberName) continue
                if (function.kind == CppFunctionKind.LAMBDA) continue
                if (function.ownerType != expectedOwner) continue
                candidates.add(CppResolvedCall(src, function))
            }
        }
        val typeSimple = type?.second?.name ?: ownerName.substringAfterLast("::")
        if (memberName == typeSimple) {
            for (src in sources) {
                src.outline.functions
                    .filter { it.kind == CppFunctionKind.CONSTRUCTOR && it.ownerType == expectedOwner }
                    .forEach { candidates.add(CppResolvedCall(src, it)) }
            }
        }
        return pickBest(candidates, argCount)
    }

    private fun pickNamespaceFunction(
        sources: List<CppFlowSource>,
        namespace: String?,
        name: String,
        argCount: Int
    ): CppResolvedCall? {
        if (namespace == null) return null
        val candidates = sources.flatMap { src ->
            src.outline.functions
                .filter { it.name == name && it.ownerType == null && it.namespacePath == namespace }
                .map { CppResolvedCall(src, it) }
        }
        return pickBest(candidates, argCount)
    }

    private fun pickBest(candidates: List<CppResolvedCall>, argCount: Int): CppResolvedCall? {
        if (candidates.isEmpty()) return null
        val distinct = candidates.distinctBy { it.source.filePath to it.definition.startOffset }
        val collapsed = distinct
            .groupBy { it.definition.qualifiedName to it.definition.parameters.size }
            .values
            .map { group -> group.firstOrNull { it.definition.hasBody } ?: group.first() }
        val arity = collapsed.filter { it.definition.parameters.size == argCount }
        val pool = when {
            arity.size == 1 -> arity
            arity.isEmpty() && collapsed.size == 1 -> collapsed
            arity.isEmpty() -> return null
            else -> {
                val names = arity.map { it.definition.qualifiedName }.distinct()
                if (names.size == 1) listOf(arity.firstOrNull { it.definition.hasBody } ?: arity.first())
                else return null
            }
        }
        return pool.singleOrNull()
    }

    private fun uniqueTypeNamed(
        sources: List<CppFlowSource>,
        name: String
    ): Pair<CppFlowSource, CppTypeDef>? {
        val exact = sources.mapNotNull { src ->
            src.outline.types.firstOrNull { it.qualifiedName == name }?.let { src to it }
        }
        val uniqueExact = exact.distinctBy { it.second.qualifiedName }
        if (uniqueExact.size == 1) return uniqueExact.first()
        if (uniqueExact.size > 1) return uniqueExact.firstOrNull { it.second.hasBody } ?: uniqueExact.first()

        val simple = name.substringAfterLast("::")
        val simpleMatches = sources
            .flatMap { src -> src.outline.types.filter { it.name == simple }.map { src to it } }
            .distinctBy { it.second.qualifiedName }
        return if (simpleMatches.size == 1) simpleMatches.single() else null
    }

    private fun enclosingOwner(source: CppFlowSource, offset: Int): String? =
        source.outline.enclosingMethodTypeAt(offset)
            ?: source.outline.enclosingTypeAt(offset)?.qualifiedName

    private fun localTypeOf(source: CppFlowSource, offset: Int, name: String): String? {
        val function = source.outline.functionContaining(offset) ?: return null
        source.outline.typeEnvironment(function)[name]?.let { return cppEffectiveTypeName(it) }
        val parent = source.outline.functions
            .filter {
                it.ownerType != null &&
                    it.startOffset != function.startOffset &&
                    offset >= it.startOffset &&
                    offset <= it.endOffset
            }
            .minByOrNull { it.endOffset - it.startOffset }
        if (parent != null) {
            source.outline.typeEnvironment(parent)[name]?.let { return cppEffectiveTypeName(it) }
        }
        return null
    }

    private fun fieldTypeOf(
        source: CppFlowSource,
        sources: List<CppFlowSource>,
        offset: Int,
        name: String
    ): String? {
        val owner = enclosingOwner(source, offset) ?: return null
        val type = uniqueTypeNamed(sources, owner) ?: return null
        val field = type.second.fields.firstOrNull { it.name == name } ?: return null
        return cppEffectiveTypeName(field.typeName)
    }

    private fun uniqueSources(
        source: CppFlowSource,
        searchSources: List<CppFlowSource>
    ): List<CppFlowSource> {
        val result = LinkedHashMap<String, CppFlowSource>()
        result[source.filePath] = source
        for (candidate in searchSources) result.putIfAbsent(candidate.filePath, candidate)
        return result.values.toList()
    }
}

/** Structural name matching used only to relate an instance (`engine`) to a class (`Engine`). */
internal object CppNameMatcher {

    private const val MIN_FUZZY_LENGTH = 3

    fun score(qualifier: String, candidate: String): Int {
        val left = normalize(qualifier)
        val right = normalize(candidate)
        if (left.isEmpty() || right.isEmpty()) return 0
        if (left == right) return 100
        if (left.length < MIN_FUZZY_LENGTH || right.length < MIN_FUZZY_LENGTH) return 0
        if (right.endsWith(left) || left.endsWith(right)) return 70
        if (right.contains(left) || left.contains(right)) return 50
        return 0
    }

    private fun normalize(value: String): String = value.filter { it.isLetterOrDigit() }.lowercase()
}

// ---------------------------------------------------------------------------------------
// Analysis context - per-run cache and include resolution
// ---------------------------------------------------------------------------------------

/**
 * Per-run cache for parsed C++ files and the only place that touches the filesystem.
 *
 * The file opened in the editor is read through PSI so unsaved edits are respected; headers reached
 * through quoted `#include` directives are read from disk. System includes (`<...>`) are deliberately
 * not followed: they are library internals and would turn the flow into standard-library noise.
 */
internal class CppAnalysisContext(val basePath: String?) {

    private val sources = HashMap<String, CppFlowSource?>()

    fun sourceForFile(file: PsiFile?): CppFlowSource? {
        if (file == null) return null
        val path = try {
            file.virtualFile?.path
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            null
        } ?: return null
        sources[path]?.let { return it }
        val text = try {
            file.text
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            null
        } ?: return null
        val source = buildSource(path, text, LanguageKindDetector.detect(file))
        sources[path] = source
        return source
    }

    fun sourceForPath(path: String, hint: LanguageKind): CppFlowSource? {
        if (sources.containsKey(path)) return sources[path]
        val source = readFromDisk(path, hint)
        sources[path] = source
        return source
    }

    /** Resolves a quoted include against the including file's directory, then the project base path. */
    fun resolveInclude(fromPath: String, include: CppInclude): String? {
        if (include.isSystem || include.target.isBlank()) return null
        val directory = File(fromPath).parentFile ?: return null
        val candidates = mutableListOf(File(directory, include.target))
        basePath?.let { candidates.add(File(it, include.target)) }
        return candidates.firstOrNull { it.isFile }?.canonicalFile?.absolutePath
    }

    /** Parsed project headers reachable from [source] through quoted includes (bounded BFS). */
    fun includedSources(source: CppFlowSource): List<CppFlowSource> {
        val found = LinkedHashMap<String, CppFlowSource>()
        var frontier = listOf(source)
        var depth = 0
        while (depth < MAX_INCLUDE_DEPTH && frontier.isNotEmpty() && found.size < MAX_INCLUDE_FILES) {
            val next = mutableListOf<CppFlowSource>()
            for (current in frontier) {
                for (include in current.outline.includes) {
                    if (found.size >= MAX_INCLUDE_FILES) break
                    val path = resolveInclude(current.filePath, include) ?: continue
                    if (path == source.filePath || found.containsKey(path)) continue
                    val parsed = sourceForPath(path, source.languageKind) ?: continue
                    found[path] = parsed
                    next.add(parsed)
                }
            }
            frontier = next
            depth++
        }
        return found.values.toList()
    }

    /**
     * Files that may legally contribute declarations: the current translation unit, quoted
     * project-local includes, and the same-directory companion (`.cpp`/`.hpp` pair). Never walks
     * the rest of the filesystem.
     */
    fun resolutionSources(source: CppFlowSource): List<CppFlowSource> {
        val ordered = LinkedHashMap<String, CppFlowSource>()
        ordered[source.filePath] = source
        for (include in includedSources(source)) ordered.putIfAbsent(include.filePath, include)
        for (companion in companionSources(source)) {
            ordered.putIfAbsent(companion.filePath, companion)
            for (include in includedSources(companion)) ordered.putIfAbsent(include.filePath, include)
        }
        return ordered.values.toList()
    }

    private fun companionSources(source: CppFlowSource): List<CppFlowSource> {
        val file = File(source.filePath)
        val directory = file.parentFile ?: return emptyList()
        val stem = file.nameWithoutExtension
        return COMPANION_EXTENSIONS
            .map { File(directory, "$stem.$it") }
            .filter { it.isFile }
            .mapNotNull { candidate ->
                val path = try {
                    candidate.canonicalFile.absolutePath
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (t: Throwable) {
                    candidate.absolutePath
                }
                if (path == source.filePath) null
                else sourceForPath(path, source.languageKind)
            }
    }

    private fun readFromDisk(path: String, hint: LanguageKind): CppFlowSource? = try {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            null
        } else {
            buildSource(path, file.readText(), hint)
        }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (t: Throwable) {
        null
    }

    private fun buildSource(path: String, text: String, hint: LanguageKind): CppFlowSource {
        val byExtension = LanguageKind.fromExtension(File(path).extension)
        val languageKind = if (byExtension == LanguageKind.UNKNOWN) hint else byExtension
        return CppFlowSource(path, text, CppFileOutline.parse(text), languageKind)
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        const val MAX_INCLUDE_DEPTH = 4
        const val MAX_INCLUDE_FILES = 32
        val COMPANION_EXTENSIONS = listOf("hpp", "h", "hh", "hxx", "cpp", "cc", "cxx")
    }
}
