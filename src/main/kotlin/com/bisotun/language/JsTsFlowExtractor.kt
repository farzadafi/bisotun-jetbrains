package com.bisotun.language

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.domain.SourceRange
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.Language
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.PsiReference
import java.io.File

/**
 * JS/TS/JSX/TSX strategy and **the single home of every JavaScript/TypeScript extraction concern**.
 *
 * Previously this feature was spread over `JsTsResolver`, `JsTsResolverTierA`, `JsTsResolverTierB`,
 * `AnalysisContext`, `FlowSource`, `JsFileOutline`, `JsFileOutlineBuilder`, `JsTokenizer`,
 * `JsCallResolver`, `JsBindingMatcher` and `JsPluginAvailability`. Those intermediate files are gone;
 * parsing, call resolution and scope building for the JS family now live in this file.
 *
 * Two strategies are used, chosen at runtime and never mixed with the Java path:
 *
 *  - **PSI strategy** - used when a JavaScript/TypeScript language plugin actually parses the file.
 *    Only platform APIs are touched (element types, [PsiReference.multiResolve]); no JavaScript PSI
 *    class is referenced, because that plugin is optional for the IntelliJ IDEA Community target.
 *  - **Tokenizer strategy** - a bounded hand written parser (no regex, no synthetic PSI) over the
 *    file text. It is the only viable option in Community builds, where `.tsx`/`.js` files arrive as
 *    plain text, and it is also the fallback when the PSI strategy finds no function-like scope.
 *
 * Caret validation belongs to this class, never to the action: a JS/TS file may have no `PsiMethod`
 * at all, so the caret is validated against real scopes (function, `{...}` block, module) using
 * offsets, which behaves identically with and without JavaScript PSI.
 */
class JsTsFlowExtractor(private val project: Project) : LanguageFlowExtractor {

    private val log = Logger.getInstance(JsTsFlowExtractor::class.java)
    private val callResolver = JsCallResolver()
    private val jsPluginId: PluginId = PluginId.getId("JavaScript")

    override val supportedLanguages: Set<LanguageKind> = setOf(
        LanguageKind.JS,
        LanguageKind.JSX,
        LanguageKind.TS,
        LanguageKind.TSX
    )

    /** One expandable scope of the tokenizer strategy: a function, a `{...}` block or the module. */
    private data class TokenizerScope(
        val startOffset: Int,
        val endOffset: Int,
        val displayName: String,
        val kind: NodeKind
    )

    private data class TokenizerHandle(val source: FlowSource, val scope: TokenizerScope)

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = LanguageKindDetector.detect(file)
        val caretOffset = caretOffset(file, editor)

        if (!isJsPluginAvailable()) {
            return extractWithTokenizer(file, caretOffset, languageKind, maxDepth, includeModels)
        }

        val psiOutcome = extractWithPsi(file, caretOffset, languageKind, maxDepth, includeModels)
        if (psiOutcome.isSuccess) return psiOutcome

        // A JS plugin can be installed while this very file is still parsed as plain text (for
        // example an unassociated file type). The tokenizer is the honest fallback in that case; the
        // reason the PSI strategy gave up is preserved as a warning in the output.
        val tokenizedOutcome = extractWithTokenizer(file, caretOffset, languageKind, maxDepth, includeModels)
        if (!tokenizedOutcome.isSuccess) return psiOutcome
        val note = psiOutcome.message ?: PSI_FALLBACK_NOTE
        return tokenizedOutcome.copy(
            result = tokenizedOutcome.result?.let { result -> result.copy(warnings = result.warnings + note) }
        )
    }

    private fun caretOffset(file: PsiFile, editor: Editor): Int {
        val textLength = try {
            file.textLength
        } catch (t: Throwable) {
            0
        }
        return editor.caretModel.offset.coerceIn(0, maxOf(textLength - 1, 0))
    }

    // ---------------------------------------------------------------------------------------
    // Tokenizer strategy (no JavaScript PSI required - IntelliJ IDEA Community)
    // ---------------------------------------------------------------------------------------

    private fun extractWithTokenizer(
        file: PsiFile,
        caretOffset: Int,
        languageKind: LanguageKind,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val context = AnalysisContext(project.basePath)
        val source = context.sourceForFile(file)
            ?: return FlowExtractionOutcome.failure(languageKind, "Unable to read this file for tokenizing.")
        val scope = resolveScope(source.outline, caretOffset)
            ?: return FlowExtractionOutcome.failure(languageKind, NO_SCOPE_MESSAGE)
        val result = buildTokenizedFlow(source, scope, maxDepth, includeModels, context)
        return FlowExtractionOutcome(result, source.languageKind)
    }

    /**
     * Caret -> scope, decided on real source offsets only (never on PSI). Order of preference:
     * innermost function, innermost `{...}` block, module scope - the last one only when the file
     * really declares something to crawl, so an empty or unparsable file still reports a message
     * instead of producing an empty flow.
     */
    private fun resolveScope(outline: JsFileOutline, caretOffset: Int): TokenizerScope? {
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
        source: FlowSource,
        scope: TokenizerScope,
        maxDepth: Int,
        includeModels: Boolean,
        context: AnalysisContext
    ): FlowResult {
        val rootNode = nodeFor(source, scope, ResolutionStatus.RESOLVED)
        val visitedScopes = mutableListOf(TokenizerHandle(source, scope))
        val nodes = mutableListOf<FlowNode>()
        val visitedNodeIds = mutableSetOf(rootNode.id)

        if (maxDepth > 0) {
            var frontier = listOf(TokenizerHandle(source, scope))
            var depth = 1
            while (depth <= maxDepth && frontier.isNotEmpty()) {
                val next = mutableListOf<TokenizerHandle>()
                for (handle in frontier) {
                    val sites = handle.source.outline.callSitesIn(handle.scope.startOffset, handle.scope.endOffset)
                    for (site in sites) {
                        val resolved = resolveTokenizedCall(handle.source, site, context)
                        if (resolved != null) {
                            val targetScope = TokenizerScope(
                                resolved.definition.startOffset,
                                resolved.definition.endOffset,
                                resolved.definition.displayName,
                                resolved.definition.kind
                            )
                            val node = nodeFor(resolved.source, targetScope, ResolutionStatus.RESOLVED)
                            if (!visitedNodeIds.add(node.id)) continue
                            nodes.add(node)
                            val nextHandle = TokenizerHandle(resolved.source, targetScope)
                            visitedScopes.add(nextHandle)
                            if (depth < maxDepth) next.add(nextHandle)
                        } else {
                            if (isBuiltinGlobal(site, handle.source)) continue
                            val node = unresolvedNode(handle.source, site)
                            if (visitedNodeIds.add(node.id)) nodes.add(node)
                        }
                    }
                }
                frontier = next
                depth++
            }
        }

        val models = if (includeModels) collectTokenizedModels(visitedScopes, context) else emptyList()
        return FlowResult(rootNode, nodes, models, listOf(FALLBACK_WARNING))
    }

    /**
     * Conservative call resolution: same-file declarations first, then exact imports, then instance
     * qualifiers matched against imported bindings. Calls without a real declaration stay unresolved
     * on purpose - guessing would produce wrong flows.
     */
    private fun resolveTokenizedCall(
        source: FlowSource,
        site: JsCallSite,
        context: AnalysisContext
    ): ResolvedCall? = callResolver.resolve(source, site) { from, specifier ->
        importedSource(from, specifier, context)
    }

    private fun importedSource(source: FlowSource, specifier: String, context: AnalysisContext): FlowSource? {
        val targetPath = context.resolveSpecifier(source.filePath, specifier) ?: return null
        return context.sourceForPath(targetPath, source.languageKind)
    }

    private fun isBuiltinGlobal(site: JsCallSite, source: FlowSource): Boolean {
        if (source.outline.functionNamed(site.name) != null) return false
        if (source.outline.importFor(site.rootIdentifier) != null) return false
        return site.rootIdentifier in BUILTIN_GLOBALS
    }

    /** Models are only reported when a declaration of the used type is really parsed. */
    private fun collectTokenizedModels(handles: List<TokenizerHandle>, context: AnalysisContext): List<FlowNode> {
        val models = LinkedHashMap<String, FlowNode>()
        for (handle in handles) {
            val source = handle.source
            val names = source.outline.identifiersIn(handle.scope.startOffset, handle.scope.endOffset)
            for (name in names) {
                val localType = source.outline.typeNamed(name)
                if (localType != null) {
                    addTokenizedModel(models, source, localType)
                    continue
                }
                val binding = source.outline.importFor(name) ?: continue
                val importedName = binding.importedName ?: continue
                val targetSource = importedSource(source, binding.specifier, context) ?: continue
                val importedType = targetSource.outline.typeNamed(importedName) ?: continue
                addTokenizedModel(models, targetSource, importedType)
            }
        }
        return models.values.toList()
    }

    private fun addTokenizedModel(models: MutableMap<String, FlowNode>, source: FlowSource, type: JsTypeDef) {
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

    private fun nodeFor(source: FlowSource, scope: TokenizerScope, status: ResolutionStatus): FlowNode {
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

    private fun unresolvedNode(source: FlowSource, site: JsCallSite): FlowNode {
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

    /**
     * The JavaScript/TypeScript plugin is optional for IntelliJ IDEA Community builds, so it is
     * probed at runtime and never referenced as a compile time dependency.
     */
    private fun isJsPluginAvailable(): Boolean = try {
        Language.findLanguageByID("JavaScript") != null || PluginManagerCore.isPluginInstalled(jsPluginId)
    } catch (t: Throwable) {
        log.debug("Unable to detect the JavaScript plugin; using the tokenizer strategy", t)
        false
    }

    // ---------------------------------------------------------------------------------------
    // PSI strategy (JavaScript plugin available)
    // ---------------------------------------------------------------------------------------

    private fun extractWithPsi(
        file: PsiFile,
        caretOffset: Int,
        languageKind: LanguageKind,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val root = outermostFunctionLike(file.findElementAt(caretOffset))
            ?: return FlowExtractionOutcome.failure(languageKind, NO_SCOPE_MESSAGE)
        val result = try {
            buildPsiFlow(root, languageKind, maxDepth, includeModels)
        } catch (t: Throwable) {
            log.warn("PSI flow extraction failed; the tokenizer strategy will be used instead", t)
            return FlowExtractionOutcome.failure(
                languageKind,
                "Could not analyze this function: ${t.message ?: "unknown error"}"
            )
        }
        return FlowExtractionOutcome(result, languageKind)
    }

    private fun buildPsiFlow(
        root: PsiElement,
        languageKind: LanguageKind,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowResult {
        val rootNode = psiNode(root, languageKind, classify(root), ResolutionStatus.RESOLVED)
        val crawled = mutableListOf(root)
        val unresolvedNodes = LinkedHashMap<String, FlowNode>()
        if (maxDepth > 0) {
            val visited = mutableSetOf(rootNode.id)
            var frontier = listOf(root)
            var depth = 1
            while (depth <= maxDepth && frontier.isNotEmpty()) {
                val next = mutableListOf<PsiElement>()
                for (function in frontier) {
                    val sites = psiCallSites(function)
                    for (target in sites.targets) {
                        val node = psiNode(target, languageKind)
                        if (!visited.add(node.id)) continue
                        crawled.add(target)
                        if (depth < maxDepth) next.add(target)
                    }
                    for (call in sites.unresolved) {
                        val node = psiNode(call, languageKind, NodeKind.UNKNOWN, ResolutionStatus.UNRESOLVED)
                        unresolvedNodes.putIfAbsent(node.id, node)
                    }
                }
                frontier = next
                depth++
            }
        }
        val nodes = crawled.drop(1).map { psiNode(it, languageKind) } + unresolvedNodes.values
        val models = if (includeModels) collectPsiModels(crawled, languageKind) else emptyList()
        return FlowResult(rootNode, nodes, models, warnings = emptyList())
    }

    private fun psiNode(
        element: PsiElement,
        fallback: LanguageKind,
        kind: NodeKind = classify(element),
        status: ResolutionStatus = ResolutionStatus.RESOLVED
    ): FlowNode = PsiFlowNodes.fromElement(
        project,
        element,
        languageKindOf(element, fallback),
        kind,
        status
    )

    private fun languageKindOf(element: PsiElement, fallback: LanguageKind): LanguageKind {
        val detected = LanguageKindDetector.detect(element.containingFile)
        return if (detected == LanguageKind.UNKNOWN) fallback else detected
    }

    /**
     * Models are the type declarations (interfaces, type aliases, classes, enums) that the crawled
     * functions genuinely reference and resolve to. Class components are skipped because they are
     * already part of the flow through their `render` method, and a declaration outside project
     * content is never reported.
     */
    private fun collectPsiModels(functions: List<PsiElement>, fallback: LanguageKind): List<FlowNode> {
        val models = LinkedHashMap<String, FlowNode>()
        for (function in functions) {
            walkResolvedTargets(function) { target ->
                if (!isModelTarget(target) || !isInContent(target.containingFile)) return@walkResolvedTargets
                val node = PsiFlowNodes.fromElement(
                    project,
                    target,
                    languageKindOf(target, fallback),
                    NodeKind.MODEL,
                    ResolutionStatus.RESOLVED,
                    (target as? PsiNamedElement)?.name ?: "model"
                )
                models.putIfAbsent(node.id, node)
            }
        }
        return models.values.toList()
    }

    private fun isModelTarget(element: PsiElement): Boolean {
        if (element is PsiFile || !isTypeDeclaration(element)) return false
        return renderMethodOf(element) == null
    }

    private fun isTypeDeclaration(element: PsiElement): Boolean {
        val elementType = elementTypeOf(element)
        if (elementType.contains("INTERFACE") || elementType.contains("TYPE_ALIAS") ||
            elementType.contains("ENUM") || elementType.contains("CLASS")
        ) {
            return true
        }
        val className = element.javaClass.simpleName
        return className.contains("Class") || className.contains("Interface") || className.contains("TypeAlias")
    }

    private data class PsiCallSites(val targets: List<PsiElement>, val unresolved: List<PsiElement>)

    /**
     * Calls made from [function]: every reference inside the element is resolved, keeping only
     * function-like targets in project content. Class components (JSX element types that resolve to
     * a class) map to their `render` method so their body becomes part of the flow. Calls that do
     * not resolve to anything real are reported as `UNRESOLVED` nodes, never guessed.
     */
    private fun psiCallSites(function: PsiElement): PsiCallSites {
        if (!isInContent(function.containingFile)) return PsiCallSites(emptyList(), emptyList())
        val targets = LinkedHashMap<String, PsiElement>()
        val unresolved = LinkedHashMap<String, PsiElement>()
        function.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element === function) return
                val isCall = isCallExpression(element)
                for (reference in referencesOf(element)) {
                    val target = resolveReference(reference)
                    if (target != null && target !== function && target !is PsiFile) {
                        if (!isInContent(target.containingFile)) continue
                        val expandable = expandableTarget(target) ?: continue
                        targets.putIfAbsent(PsiFlowNodes.nodeId(expandable), expandable)
                    } else if (isCall && target == null) {
                        unresolved.putIfAbsent(PsiFlowNodes.nodeId(element), element)
                    }
                }
            }
        })
        return PsiCallSites(targets.values.toList(), unresolved.values.toList())
    }

    /** Walks [root] and reports every reference target that resolves successfully. */
    private fun walkResolvedTargets(root: PsiElement, action: (PsiElement) -> Unit) {
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)
                if (element === root) return
                for (reference in referencesOf(element)) {
                    val target = resolveReference(reference) ?: continue
                    if (target === root || target is PsiFile) continue
                    action(target)
                }
            }
        })
    }

    private fun referencesOf(element: PsiElement): Array<PsiReference> = try {
        element.references
    } catch (t: Throwable) {
        emptyArray()
    }

    private fun resolveReference(reference: PsiReference): PsiElement? = try {
        if (reference is PsiPolyVariantReference) {
            reference.multiResolve(true).firstOrNull()?.element
        } else {
            reference.resolve()
        }
    } catch (t: Throwable) {
        log.debug("Reference resolution failed in the JS/TS PSI strategy", t)
        null
    }

    /** Function itself, or the `render` method when the reference points at a class component. */
    private fun expandableTarget(target: PsiElement): PsiElement? = when {
        isClassLike(target) -> renderMethodOf(target)
        isFunctionLike(target) -> target
        else -> null
    }

    private fun outermostFunctionLike(element: PsiElement?): PsiElement? {
        var result: PsiElement? = null
        var current: PsiElement? = element
        while (current != null && current !is PsiFile) {
            if (isFunctionLike(current)) result = current
            current = current.parent
        }
        if (result != null) return result
        var owner: PsiElement? = element
        while (owner != null && owner !is PsiFile) {
            if (isClassLike(owner)) return renderMethodOf(owner)
            owner = owner.parent
        }
        return null
    }

    private fun renderMethodOf(classElement: PsiElement): PsiElement? {
        var child = classElement.firstChild
        while (child != null) {
            if (isFunctionLike(child) && (child as? PsiNamedElement)?.name == "render") return child
            child = child.nextSibling
        }
        return null
    }

    private fun isFunctionLike(element: PsiElement): Boolean {
        if (element is PsiFile) return false
        val elementType = elementTypeOf(element)
        if (elementType.contains("TYPE")) return false
        if (elementType.contains("FUNCTION") || elementType.contains("LAMBDA")) return true
        if (elementType == "METHOD" || elementType.endsWith("_METHOD")) return true
        val className = element.javaClass.simpleName
        if (className.contains("Type")) return false
        return className.contains("Function") || className.contains("Lambda") || className.contains("Method")
    }

    private fun isClassLike(element: PsiElement): Boolean {
        if (element is PsiFile) return false
        val elementType = elementTypeOf(element)
        if (elementType.contains("INTERFACE") || elementType.contains("TYPE_ALIAS")) return false
        return elementType.contains("CLASS") || element.javaClass.simpleName.contains("Class")
    }

    private fun isCallExpression(element: PsiElement): Boolean {
        val elementType = elementTypeOf(element)
        if (elementType.contains("SIGNATURE")) return false
        return elementType.contains("CALL") || element.javaClass.simpleName.contains("CallExpression")
    }

    private fun classify(element: PsiElement): NodeKind {
        val elementType = elementTypeOf(element)
        val className = element.javaClass.simpleName
        if (className.contains("Method") || elementType.contains("METHOD")) return NodeKind.METHOD
        val name = (element as? PsiNamedElement)?.name
        if (name != null && name.firstOrNull()?.isUpperCase() == true) return NodeKind.COMPONENT
        return NodeKind.FUNCTION
    }

    private fun elementTypeOf(element: PsiElement): String = try {
        element.node?.elementType?.toString().orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun isInContent(file: PsiFile?): Boolean {
        val virtualFile = file?.virtualFile ?: return false
        return try {
            ProjectFileIndex.getInstance(project).isInContent(virtualFile)
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        const val NO_SCOPE_MESSAGE =
            "Please place the caret inside a function, component or class method body."
        const val BLOCK_SCOPE_NAME = "block()"
        const val MODULE_SCOPE_NAME = "module()"

        const val PSI_FALLBACK_NOTE =
            "ℹ The JavaScript plugin could not describe this file, so the bounded tokenizer was used."

        const val FALLBACK_WARNING =
            "⚠ JS/TS fallback mode (no JavaScript plugin): extraction uses a bounded tokenizer, " +
                "so resolution is best effort and unresolved calls are marked as such."

        val BUILTIN_GLOBALS = setOf(
            "console", "window", "document", "globalThis", "global", "Math", "JSON", "Object", "Array",
            "String", "Number", "Boolean", "Date", "Promise", "Reflect", "Symbol", "Error", "RegExp",
            "Map", "Set", "WeakMap", "WeakSet", "process", "module", "exports", "localStorage",
            "sessionStorage", "navigator", "location", "history", "fetch", "require", "parseInt",
            "parseFloat", "isNaN", "isFinite", "encodeURIComponent", "decodeURIComponent", "setTimeout",
            "setInterval", "clearTimeout", "clearInterval", "alert", "structuredClone", "queueMicrotask"
        )
    }
}

// ---------------------------------------------------------------------------------------
// Moved from js/JsTokenizer.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * Token kinds produced by [JsTokenizer].
 */
internal enum class JsTokenType { IDENTIFIER, KEYWORD, STRING, NUMBER, PUNCTUATION }

/**
 * A single token of a JS/TS/JSX/TSX source file.
 */
internal data class JsToken(
    val type: JsTokenType,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int
)

/**
 * Hand written JavaScript/TypeScript tokenizer.
 *
 * This is deliberately not a regex scanner: it is a state machine that understands line comments,
 * block comments, single/double quoted strings and template literals (including `${...}`
 * interpolations with arbitrary nesting) so that no call is ever detected inside a string or a
 * comment. Regex literal bodies are not special cased; see the limitations listed on
 * [JsTsFlowExtractor].
 */
internal class JsTokenizer(private val text: String) {

    private val tokens = ArrayList<JsToken>()
    private var index = 0
    private var line = 1

    fun tokenize(): List<JsToken> {
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\n' -> advanceLine()
                ch.isWhitespace() -> index++
                ch == '/' && peek(1) == '/' -> skipLineComment()
                ch == '/' && peek(1) == '*' -> skipBlockComment()
                ch == '\'' || ch == '"' -> readQuotedString(ch)
                ch == '`' -> readTemplateLiteral()
                isIdentifierStart(ch) -> readIdentifier()
                ch.isDigit() -> readNumber()
                else -> readPunctuation()
            }
        }
        return tokens
    }

    private fun advanceLine() {
        if (index < text.length && text[index] == '\n') line++
        index++
    }

    private fun peek(offset: Int): Char? = text.getOrNull(index + offset)

    private fun skipLineComment() {
        index += 2
        while (index < text.length && text[index] != '\n') index++
    }

    private fun skipBlockComment() {
        index += 2
        while (index < text.length) {
            if (text[index] == '\n') {
                advanceLine()
                continue
            }
            if (text[index] == '*' && peek(1) == '/') {
                index += 2
                return
            }
            index++
        }
    }

    private fun readQuotedString(quote: Char) {
        val start = index
        val startLine = line
        index++
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\\' -> {
                    if (peek(1) == '\n') {
                        advanceLine()
                    } else {
                        index = (index + 2).coerceAtMost(text.length)
                    }
                }
                ch == '\n' -> break
                ch == quote -> {
                    index++
                    break
                }
                else -> index++
            }
        }
        addStringToken(start, index, startLine)
    }

    private fun addStringToken(start: Int, end: Int, startLine: Int) {
        if (end <= start) return
        tokens.add(JsToken(JsTokenType.STRING, text.substring(start, end), start, end, startLine))
    }

    private fun readTemplateLiteral() {
        var chunkStart = index
        var chunkLine = line
        index++
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\\' -> index = (index + 2).coerceAtMost(text.length)
                ch == '`' -> {
                    index++
                    addStringToken(chunkStart, index, chunkLine)
                    return
                }
                ch == '$' && peek(1) == '{' -> {
                    addStringToken(chunkStart, index, chunkLine)
                    index += 2
                    readTemplateExpression()
                    chunkStart = index
                    chunkLine = line
                }
                ch == '\n' -> advanceLine()
                else -> index++
            }
        }
        addStringToken(chunkStart, index, chunkLine)
    }

    /** Tokenizes the inside of a `${...}` interpolation, stopping after the matching `}`. */
    private fun readTemplateExpression() {
        var depth = 1
        while (index < text.length) {
            val ch = text[index]
            when {
                ch == '\n' -> advanceLine()
                ch.isWhitespace() -> index++
                ch == '/' && peek(1) == '/' -> skipLineComment()
                ch == '/' && peek(1) == '*' -> skipBlockComment()
                ch == '\'' || ch == '"' -> readQuotedString(ch)
                ch == '`' -> readTemplateLiteral()
                isIdentifierStart(ch) -> readIdentifier()
                ch.isDigit() -> readNumber()
                ch == '{' -> {
                    depth++
                    readPunctuation()
                }
                ch == '}' -> {
                    depth--
                    if (depth == 0) {
                        index++
                        return
                    }
                    readPunctuation()
                }
                else -> readPunctuation()
            }
        }
    }

    private fun readIdentifier() {
        val start = index
        while (index < text.length && isIdentifierPart(text[index])) index++
        val value = text.substring(start, index)
        val type = if (value in KEYWORDS) JsTokenType.KEYWORD else JsTokenType.IDENTIFIER
        tokens.add(JsToken(type, value, start, index, line))
    }

    private fun readNumber() {
        val start = index
        while (index < text.length) {
            val ch = text[index]
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_') index++ else break
        }
        tokens.add(JsToken(JsTokenType.NUMBER, text.substring(start, index), start, index, line))
    }

    private fun readPunctuation() {
        val start = index
        val length = when {
            matchesOperator(THREE_CHAR_OPERATORS, 3) -> 3
            matchesOperator(TWO_CHAR_OPERATORS, 2) -> 2
            else -> 1
        }
        index = (index + length).coerceAtMost(text.length)
        tokens.add(JsToken(JsTokenType.PUNCTUATION, text.substring(start, index), start, index, line))
    }

    private fun matchesOperator(operators: Set<String>, length: Int): Boolean {
        if (index + length > text.length) return false
        return text.substring(index, index + length) in operators
    }

    private companion object {
        val KEYWORDS = setOf(
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
            "return", "function", "class", "extends", "super", "this", "new", "delete", "typeof",
            "instanceof", "in", "of", "async", "await", "yield", "try", "catch", "finally", "throw",
            "var", "let", "const", "import", "export", "from", "as", "void", "null", "undefined",
            "true", "false", "static", "get", "set", "interface", "type", "enum", "implements",
            "namespace", "declare", "public", "private", "protected", "readonly", "abstract"
        )

        val TWO_CHAR_OPERATORS = setOf(
            "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=",
            "**", "<<", ">>", "|=", "&=", "%="
        )

        val THREE_CHAR_OPERATORS = setOf("===", "!==", "...", "**=", "<<=", ">>=", "??=", "&&=", "||=")

        fun isIdentifierStart(ch: Char): Boolean = ch == '_' || ch == '$' || ch.isLetter()

        fun isIdentifierPart(ch: Char): Boolean = ch == '_' || ch == '$' || ch.isLetterOrDigit()
    }
}

// ---------------------------------------------------------------------------------------
// Moved from js/JsFileOutline.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * A function-like declaration discovered by the tokenizer tier.
 *
 * [startOffset]/[endOffset] bound the declaration in its file; [nameOffset] points at the declared
 * name so call detection can avoid reporting a declaration as a call.
 */
internal data class JsFunctionDef(
    val name: String,
    val displayName: String,
    val startOffset: Int,
    val endOffset: Int,
    val nameOffset: Int,
    val kind: NodeKind
)

/**
 * One imported binding. [importedName] is null for default and namespace imports.
 */
internal data class JsImportBinding(
    val localName: String,
    val importedName: String?,
    val specifier: String,
    val isNamespace: Boolean
)

internal data class JsTypeDef(
    val name: String,
    val startOffset: Int,
    val endOffset: Int
)

/**
 * A call site inside a function body. [rawText] keeps the full chain (`service.users.list`) so an
 * unresolved node still shows what the code actually called.
 */
internal data class JsCallSite(
    val name: String,
    val qualifier: String?,
    val rootIdentifier: String,
    val offset: Int,
    val rawText: String
)

/**
 * Parsed view of a single JS/TS/JSX/TSX file built purely from [JsToken]s.
 *
 * The outline is intentionally conservative: it understands function declarations, exported
 * functions, arrow functions assigned to a const/let/var (including TS type annotations), class
 * methods, top level type declarations and import bindings. Everything else is ignored rather than
 * guessed.
 */
/** A `{...}` region of the source text, identified by character offsets. */
internal data class JsBlockSpan(val startOffset: Int, val endOffset: Int)

internal class JsFileOutline(
    val text: String,
    val functions: List<JsFunctionDef>,
    val types: List<JsTypeDef>,
    val imports: List<JsImportBinding>,
    val defaultExportName: String?,
    internal val tokens: List<JsToken>,
    internal val bracketMatches: IntArray
) {
    /** Innermost (named) function containing [offset]; prefers the latest start. */
    fun functionContaining(offset: Int): JsFunctionDef? = functions
        .filter { offset >= it.startOffset && offset <= it.endOffset }
        .maxByOrNull { it.startOffset }

    fun functionNamed(name: String): JsFunctionDef? = functions.firstOrNull { it.name == name }

    fun typeNamed(name: String): JsTypeDef? = types.firstOrNull { it.name == name }

    /** Functions declared inside [type]'s span: constructors, methods and arrow class fields. */
    fun functionsIn(type: JsTypeDef): List<JsFunctionDef> =
        functions.filter { it.startOffset >= type.startOffset && it.endOffset <= type.endOffset }

    /** Method named [name] declared by [type], or null when that type does not declare it. */
    fun methodIn(type: JsTypeDef, name: String): JsFunctionDef? =
        functionsIn(type).firstOrNull { it.name == name }

    fun importFor(localName: String): JsImportBinding? = imports.firstOrNull { it.localName == localName }

    /**
     * Calls whose callee starts inside [function]'s range. Member chains are collapsed to the last
     * segment, so `api.users.list()` yields name `list`, qualifier `users`, root `api`.
     */
    fun callSitesIn(function: JsFunctionDef): List<JsCallSite> =
        callSitesIn(function.startOffset, function.endOffset, skipNestedFunctions = false)

    /**
     * Calls whose callee starts inside `[startOffset, endOffset)`. Member chains are collapsed to
     * the last segment, so `api.users.list()` yields name `list`, qualifier `users`, root `api`.
     * When [skipNestedFunctions] is true the bodies of nested declarations are skipped, so a module
     * or block scope lists its own statements instead of every call made by the functions it holds.
     */
    fun callSitesIn(
        startOffset: Int,
        endOffset: Int,
        skipNestedFunctions: Boolean = true
    ): List<JsCallSite> {
        val sites = LinkedHashMap<Int, JsCallSite>()
        val declarationNames = functions.mapTo(HashSet()) { it.nameOffset }
        val nested = if (skipNestedFunctions) {
            functions.filter { it.startOffset > startOffset && it.endOffset <= endOffset }
        } else {
            emptyList()
        }
        var i = firstTokenIndexAtOrAfter(startOffset)
        while (i < tokens.size && tokens[i].startOffset < endOffset) {
            val token = tokens[i]
            if (token.type != JsTokenType.IDENTIFIER ||
                nested.any { token.startOffset in it.startOffset..it.endOffset }
            ) {
                i++
                continue
            }
            val chain = mutableListOf(token.text)
            var last = i
            while (true) {
                val separator = last + 1
                if (separator + 1 >= tokens.size) break
                val separatorText = tokens[separator].text
                val next = tokens[separator + 1]
                if ((separatorText == "." || separatorText == "?.") && isNameToken(next)) {
                    chain.add(next.text)
                    last = separator + 1
                } else {
                    break
                }
            }
            val openParen = last + 1
            if (openParen < tokens.size && tokens[openParen].text == "(") {
                val closeParen = bracketMatches[openParen]
                val looksLikeMethodDefinition = closeParen > 0 &&
                    tokens.getOrNull(closeParen + 1)?.text == "{" &&
                    token.startOffset !in declarationNames
                if (!looksLikeMethodDefinition && token.startOffset !in declarationNames) {
                    sites.putIfAbsent(
                        token.startOffset,
                        JsCallSite(
                            name = chain.last(),
                            qualifier = if (chain.size > 1) chain[chain.size - 2] else null,
                            rootIdentifier = chain.first(),
                            offset = token.startOffset,
                            rawText = chain.joinToString(".")
                        )
                    )
                }
            }
            i = last + 1
        }
        return sites.values.toList()
    }

    /** Identifier names used inside [function]; used for conservative type/model detection. */
    fun identifiersIn(function: JsFunctionDef): Set<String> =
        identifiersIn(function.startOffset, function.endOffset)

    /** Identifier names used inside `[startOffset, endOffset)`; never resolves anything by itself. */
    fun identifiersIn(startOffset: Int, endOffset: Int): Set<String> {
        val names = LinkedHashSet<String>()
        var i = firstTokenIndexAtOrAfter(startOffset)
        while (i < tokens.size && tokens[i].startOffset < endOffset) {
            val token = tokens[i]
            if (token.type == JsTokenType.IDENTIFIER) names.add(token.text)
            i++
        }
        return names
    }

    /**
     * Innermost `{...}` pair containing [offset], used when the caret is not inside a declared
     * function but still sits in a real block of code. Returns null outside any block.
     */
    fun blockContaining(offset: Int): JsBlockSpan? {
        var best: JsBlockSpan? = null
        for (index in tokens.indices) {
            if (tokens[index].text != "{") continue
            val close = bracketMatches[index]
            if (close <= 0) continue
            val start = tokens[index].startOffset
            val end = tokens[close].endOffset
            if (offset < start || offset > end) continue
            if (best == null || start > best.startOffset) best = JsBlockSpan(start, end)
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

    private fun isNameToken(token: JsToken): Boolean =
        token.type == JsTokenType.IDENTIFIER || token.type == JsTokenType.KEYWORD

    companion object {
        fun parse(text: String): JsFileOutline = JsFileOutlineBuilder(text).build()
    }
}

// ---------------------------------------------------------------------------------------
// Moved from js/JsFileOutlineBuilder.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * Builds a [JsFileOutline] from tokens. Declarations that cannot be understood unambiguously are
 * skipped instead of being approximated.
 */
internal class JsFileOutlineBuilder(private val text: String) {

    private val tokens = JsTokenizer(text).tokenize()
    private val bracketMatches = IntArray(tokens.size) { -1 }
    private val functions = mutableListOf<JsFunctionDef>()
    private val types = mutableListOf<JsTypeDef>()
    private val imports = mutableListOf<JsImportBinding>()
    private var defaultExportName: String? = null

    fun build(): JsFileOutline {
        matchBrackets()
        scanScope(0, tokens.size)
        return JsFileOutline(
            text = text,
            functions = functions
                .distinctBy { it.startOffset to it.endOffset }
                .sortedBy { it.startOffset },
            types = types.distinctBy { it.name to it.startOffset },
            imports = imports.distinctBy { it.localName to it.specifier },
            defaultExportName = defaultExportName,
            tokens = tokens,
            bracketMatches = bracketMatches
        )
    }

    private fun matchBrackets() {
        val stack = ArrayDeque<Int>()
        for (index in tokens.indices) {
            val tokenText = tokens[index].text
            when (tokenText) {
                "(", "[", "{" -> stack.addLast(index)
                ")", "]", "}" -> {
                    val open = indexOfMatchingOpen(stack, tokenText) ?: continue
                    bracketMatches[open] = index
                    bracketMatches[index] = open
                }
            }
        }
    }

    private fun indexOfMatchingOpen(stack: ArrayDeque<Int>, close: String): Int? {
        val expectedOpen = when (close) {
            ")" -> "("
            "]" -> "["
            else -> "{"
        }
        val temporary = ArrayList<Int>()
        var found: Int? = null
        while (stack.isNotEmpty()) {
            val candidate = stack.removeLast()
            if (tokens[candidate].text == expectedOpen) {
                found = candidate
                break
            }
            temporary.add(candidate)
        }
        for (index in temporary.asReversed()) stack.addLast(index)
        return found
    }

    private fun scanScope(from: Int, to: Int) {
        var i = from
        while (i < to && i < tokens.size) {
            val tokenText = tokens[i].text
            i = when {
                tokenText == "function" -> handleFunction(i, isAsync = false)
                tokenText == "async" && nextText(i + 1) == "function" -> handleFunction(i + 1, isAsync = true)
                tokenText == "class" -> handleClass(i)
                tokenText == "const" || tokenText == "let" || tokenText == "var" -> handleVariable(i)
                tokenText == "interface" || tokenText == "enum" -> handleNamedTypeBlock(i)
                tokenText == "type" && isTypeAliasStart(i) -> handleTypeAlias(i)
                tokenText == "import" -> handleImport(i)
                tokenText == "export" -> handleExport(i)
                else -> i + 1
            }
        }
    }

    private fun nextText(index: Int): String? = tokens.getOrNull(index)?.text

    private fun isNameToken(index: Int): Boolean {
        val token = tokens.getOrNull(index) ?: return false
        return token.type == JsTokenType.IDENTIFIER || token.type == JsTokenType.KEYWORD
    }

    private fun handleExport(index: Int): Int {
        if (nextText(index + 1) == "default") {
            val candidate = index + 2
            when {
                nextText(candidate) == "function" || nextText(candidate) == "class" -> {
                    var cursor = candidate + 1
                    if (nextText(cursor) == "*") cursor++
                    if (tokens.getOrNull(cursor)?.type == JsTokenType.IDENTIFIER) {
                        defaultExportName = tokens[cursor].text
                    }
                }
                tokens.getOrNull(candidate)?.type == JsTokenType.IDENTIFIER -> {
                    defaultExportName = tokens[candidate].text
                }
            }
        }
        return index + 1
    }

    private fun kindForName(name: String): NodeKind =
        if (name.firstOrNull()?.isUpperCase() == true) NodeKind.COMPONENT else NodeKind.FUNCTION

    private fun handleFunction(index: Int, isAsync: Boolean): Int {
        var cursor = index + 1
        if (nextText(cursor) == "*") cursor++
        val nameIndex = cursor
        val name = if (tokens.getOrNull(nameIndex)?.type == JsTokenType.IDENTIFIER) tokens[nameIndex].text else null
        val afterName = if (name != null) nameIndex + 1 else cursor
        val afterGenerics = skipTypeParameters(afterName)
        val paramsOpen = findNextToken(afterGenerics, "(", limit = afterGenerics + 12)
        if (paramsOpen == -1) return index + 1
        val paramsClose = bracketMatches[paramsOpen]
        if (paramsClose == -1) return index + 1
        val bodyOpen = findBodyBrace(paramsClose + 1)
        val endIndex = if (bodyOpen != -1 && bracketMatches[bodyOpen] != -1) bracketMatches[bodyOpen] else paramsClose
        val startOffset = if (isAsync && index > 0 && tokens[index - 1].text == "async") {
            tokens[index - 1].startOffset
        } else if (name != null) {
            tokens[nameIndex].startOffset
        } else {
            tokens[index].startOffset
        }

        if (name != null) {
            functions.add(
                JsFunctionDef(
                    name = name,
                    displayName = signature(name, paramsOpen, paramsClose),
                    startOffset = startOffset,
                    endOffset = tokens[endIndex].endOffset,
                    nameOffset = tokens[nameIndex].startOffset,
                    kind = kindForName(name)
                )
            )
        }

        if (bodyOpen != -1 && endIndex > bodyOpen) {
            scanScope(bodyOpen + 1, endIndex)
        }

        return endIndex + 1
    }

    private fun handleClass(index: Int): Int {
        val nameIndex = index + 1
        val name = if (tokens.getOrNull(nameIndex)?.type == JsTokenType.IDENTIFIER) tokens[nameIndex].text else null
        val bodyOpen = findNextToken(nameIndex, "{", limit = nameIndex + 60)
        if (bodyOpen == -1) return index + 1
        val bodyClose = bracketMatches[bodyOpen]
        if (bodyClose == -1) return index + 1
        if (name != null) {
            types.add(JsTypeDef(name, tokens[nameIndex].startOffset, tokens[bodyClose].endOffset))
            collectMethods(name, bodyOpen, bodyClose)
        }
        return bodyClose + 1
    }

    private fun collectMethods(className: String, bodyOpen: Int, bodyClose: Int) {
        var i = bodyOpen + 1
        while (i < bodyClose) {
            i = collectMember(className, i, bodyClose)
        }
    }

    private fun collectMember(className: String, start: Int, bodyClose: Int): Int {
        when (nextText(start)) {
            "@" -> return skipDecorator(start)
            ";", "," -> return start + 1
            "{" -> {
                val close = bracketMatches[start]
                if (close > start) return close + 1
            }
        }
        val afterModifiers = skipMemberModifiers(start, bodyClose)
        if (afterModifiers > start) {
            val parsed = parseMember(className, afterModifiers, bodyClose)
            if (parsed != -1) return parsed
        }
        val parsed = parseMember(className, start, bodyClose)
        return if (parsed != -1) parsed else start + 1
    }

    private fun skipMemberModifiers(from: Int, limit: Int): Int {
        var cursor = from
        while (cursor < limit && tokens[cursor].text in CLASS_MEMBER_MODIFIERS) cursor++
        return cursor
    }

    private fun parseMember(className: String, start: Int, bodyClose: Int): Int {
        var nameIndex = start
        if (nextText(nameIndex) == "#") nameIndex++
        if (!isNameToken(nameIndex)) return -1
        val name = tokens[nameIndex].text
        val paramsOpen = skipTypeParameters(nameIndex + 1)
        if (nextText(paramsOpen) == "(") {
            val paramsClose = bracketMatches[paramsOpen]
            if (paramsClose <= paramsOpen || paramsClose >= bodyClose) return nameIndex + 1
            val afterParams = paramsClose + 1
            val bodyOpen = findBodyBrace(afterParams)
            if (bodyOpen <= paramsClose || bodyOpen >= bodyClose) return nameIndex + 1
            val bodyEnd = bracketMatches[bodyOpen]
            if (bodyEnd <= bodyOpen) return nameIndex + 1
            addMethod(className, name, nameIndex, paramsOpen, bodyEnd)
            scanScope(bodyOpen + 1, bodyEnd)
            return bodyEnd + 1
        }
        val assignment = skipAsyncOperator(nameIndex + 1)
        if (nextText(assignment) != "=") return -1
        val valueStart = skipAsyncOperator(assignment + 1)
        val arrow = findArrow(valueStart)
        if (arrow == -1) return nameIndex + 1
        val end = expressionEnd(arrow + 1)
        if (end <= arrow) return nameIndex + 1
        functions.add(
            JsFunctionDef(
                name = name,
                displayName = arrowSignature("$className.$name", valueStart, arrow),
                startOffset = tokens[nameIndex].startOffset,
                endOffset = tokens[end].endOffset,
                nameOffset = tokens[nameIndex].startOffset,
                kind = NodeKind.METHOD
            )
        )
        val bodyOpen = findNextToken(arrow + 1, "{", limit = arrow + 4)
        if (bodyOpen != -1 && bracketMatches[bodyOpen] != -1) {
            scanScope(bodyOpen + 1, bracketMatches[bodyOpen])
        }
        return end + 1
    }

    private fun skipDecorator(start: Int): Int {
        var cursor = start + 1
        if (!isNameToken(cursor)) return start + 1
        cursor++
        while (nextText(cursor) == "." && isNameToken(cursor + 1)) cursor += 2
        if (nextText(cursor) == "(") {
            val close = bracketMatches[cursor]
            if (close > cursor) return close + 1
        }
        return cursor
    }

    private fun skipTypeParameters(from: Int): Int {
        if (nextText(from) != "<") return from
        var depth = 0
        var i = from
        var guard = 0
        while (i < tokens.size && guard++ < 80) {
            when (tokens[i].text) {
                "<" -> depth++
                ">" -> {
                    depth--
                    if (depth <= 0) return i + 1
                }
                ">>" -> {
                    depth -= 2
                    if (depth <= 0) return i + 1
                }
                ";", "{", "}" -> return from
            }
            i++
        }
        return from
    }

    private fun skipAsyncOperator(from: Int): Int =
        if (nextText(from) == "async") from + 1 else from

    private fun addMethod(className: String, name: String, nameIndex: Int, paramsOpen: Int, bodyEnd: Int) {
        functions.add(
            JsFunctionDef(
                name = name,
                displayName = signature("$className.$name", paramsOpen, bracketMatches[paramsOpen]),
                startOffset = tokens[nameIndex].startOffset,
                endOffset = tokens[bodyEnd].endOffset,
                nameOffset = tokens[nameIndex].startOffset,
                kind = NodeKind.METHOD
            )
        )
    }

    private fun signature(name: String, paramsOpen: Int, paramsClose: Int): String {
        if (paramsOpen !in tokens.indices || paramsClose !in tokens.indices) return name
        if (paramsClose < paramsOpen) return name
        val start = tokens[paramsOpen].endOffset
        val end = tokens[paramsClose].startOffset
        if (end <= start) return "$name()"
        val compact = text.substring(start, end)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (compact.isEmpty()) "$name()" else "$name($compact)"
    }

    private fun arrowSignature(name: String, paramsStart: Int, arrow: Int): String {
        val first = tokens.getOrNull(paramsStart) ?: return name
        if (first.text == "(") {
            val close = bracketMatches[paramsStart]
            if (close in paramsStart until arrow) return signature(name, paramsStart, close)
        }
        if (first.type == JsTokenType.IDENTIFIER) return "$name(${first.text})"
        return name
    }

    private fun handleVariable(index: Int): Int {
        val nameIndex = index + 1
        val nameToken = tokens.getOrNull(nameIndex) ?: return index + 1
        if (nameToken.type != JsTokenType.IDENTIFIER) return index + 1
        val equalsIndex = findAssignmentOperator(nameIndex + 1)
        if (equalsIndex == -1) return nameIndex + 1
        var valueIndex = equalsIndex + 1
        if (nextText(valueIndex) == "async") valueIndex++
        if (nextText(valueIndex) == "function") {
            val paramsOpen = findNextToken(valueIndex, "(", limit = valueIndex + 12)
            if (paramsOpen == -1) return equalsIndex + 1
            val paramsClose = bracketMatches[paramsOpen]
            if (paramsClose == -1) return equalsIndex + 1
            val bodyOpen = findBodyBrace(paramsClose + 1)
            val endIndex = if (bodyOpen != -1 && bracketMatches[bodyOpen] != -1) bracketMatches[bodyOpen] else paramsClose
            functions.add(
                JsFunctionDef(
                    name = nameToken.text,
                    displayName = signature(nameToken.text, paramsOpen, paramsClose),
                    startOffset = nameToken.startOffset,
                    endOffset = tokens[endIndex].endOffset,
                    nameOffset = nameToken.startOffset,
                    kind = kindForName(nameToken.text)
                )
            )
            if (bodyOpen != -1 && endIndex > bodyOpen) {
                scanScope(bodyOpen + 1, endIndex)
            }
            return endIndex + 1
        }
        val arrow = findArrow(valueIndex)
        if (arrow == -1) return equalsIndex + 1
        val end = expressionEnd(arrow + 1)
        if (end <= arrow) return equalsIndex + 1
        functions.add(
            JsFunctionDef(
                name = nameToken.text,
                displayName = arrowSignature(nameToken.text, valueIndex, arrow),
                startOffset = nameToken.startOffset,
                endOffset = tokens[end].endOffset,
                nameOffset = nameToken.startOffset,
                kind = kindForName(nameToken.text)
            )
        )
        val bodyOpen = findNextToken(arrow + 1, "{", limit = arrow + 4)
        if (bodyOpen != -1 && bracketMatches[bodyOpen] != -1) {
            scanScope(bodyOpen + 1, bracketMatches[bodyOpen])
        }
        return end + 1
    }

    private fun handleNamedTypeBlock(index: Int): Int {
        val nameIndex = index + 1
        val name = tokens.getOrNull(nameIndex)?.takeIf { it.type == JsTokenType.IDENTIFIER }?.text
            ?: return index + 1
        val bodyOpen = findNextToken(nameIndex, "{", limit = nameIndex + 40)
        if (bodyOpen == -1) return index + 1
        val bodyClose = bracketMatches[bodyOpen]
        if (bodyClose == -1) return index + 1
        types.add(JsTypeDef(name, tokens[nameIndex].startOffset, tokens[bodyClose].endOffset))
        return bodyClose + 1
    }

    private fun isTypeAliasStart(index: Int): Boolean {
        if (tokens.getOrNull(index + 1)?.type != JsTokenType.IDENTIFIER) return false
        return nextText(index + 2) == "=" || nextText(index + 2) == "<"
    }

    private fun handleTypeAlias(index: Int): Int {
        val name = tokens[index + 1].text
        val end = expressionEnd(index + 2)
        if (end <= index + 1) return index + 1
        types.add(JsTypeDef(name, tokens[index + 1].startOffset, tokens[end].endOffset))
        return end + 1
    }

    private fun handleImport(index: Int): Int {
        var cursor = index + 1
        if (nextText(cursor) == "type") cursor++
        val pending = mutableListOf<ImportCandidate>()
        when {
            nextText(cursor) == "{" -> {
                val close = bracketMatches[cursor]
                if (close == -1) return index + 1
                collectNamedBindings(cursor + 1, close, pending)
                cursor = close + 1
            }
            nextText(cursor) == "*" -> {
                if (nextText(cursor + 1) != "as" || !isNameToken(cursor + 2)) return index + 1
                pending.add(ImportCandidate(tokens[cursor + 2].text, null, true))
                cursor += 3
            }
            isNameToken(cursor) && nextText(cursor) != "from" -> {
                pending.add(ImportCandidate(tokens[cursor].text, null, false))
                cursor += 1
                if (nextText(cursor) == ",") {
                    cursor++
                    if (nextText(cursor) == "{") {
                        val close = bracketMatches[cursor]
                        if (close == -1) return index + 1
                        collectNamedBindings(cursor + 1, close, pending)
                        cursor = close + 1
                    } else if (nextText(cursor) == "*" && nextText(cursor + 1) == "as" && isNameToken(cursor + 2)) {
                        pending.add(ImportCandidate(tokens[cursor + 2].text, null, true))
                        cursor += 3
                    }
                }
            }
            else -> return index + 1
        }
        if (nextText(cursor) != "from") return index + 1
        val specifierToken = tokens.getOrNull(cursor + 1) ?: return index + 1
        if (specifierToken.type != JsTokenType.STRING) return index + 1
        val specifier = specifierToken.text.trim('\'', '"', '`')
        for (candidate in pending) {
            imports.add(JsImportBinding(candidate.localName, candidate.importedName, specifier, candidate.isNamespace))
        }
        return cursor + 2
    }

    private fun collectNamedBindings(from: Int, to: Int, pending: MutableList<ImportCandidate>) {
        var i = from
        while (i < to) {
            if (tokens[i].text == "type") {
                i++
                continue
            }
            if (tokens[i].type != JsTokenType.IDENTIFIER) {
                i++
                continue
            }
            val imported = tokens[i].text
            if (nextText(i + 1) == "as" && isNameToken(i + 2)) {
                pending.add(ImportCandidate(tokens[i + 2].text, imported, false))
                i += 3
            } else {
                pending.add(ImportCandidate(imported, imported, false))
                i += 1
            }
        }
    }

    private data class ImportCandidate(val localName: String, val importedName: String?, val isNamespace: Boolean)

    private fun findNextToken(from: Int, expected: String, limit: Int): Int {
        var i = from
        while (i < tokens.size && i <= limit) {
            val tokenText = tokens[i].text
            if (tokenText == expected) return i
            if (tokenText == ";" || tokenText == "}") return -1
            i++
        }
        return -1
    }

    /** Finds the `=` of a variable declaration, correctly handling TypeScript type annotations. */
    private fun findAssignmentOperator(from: Int): Int {
        var i = from
        var guard = 0
        while (i < tokens.size && guard++ < 120) {
            when (tokens[i].text) {
                "=" -> return i
                ";", "}" -> return -1
                "<" -> {
                    val afterGeneric = skipTypeParameters(i)
                    if (afterGeneric > i) {
                        i = afterGeneric
                        continue
                    }
                }
                "(", "[", "{" -> {
                    val close = bracketMatches[i]
                    if (close == -1) return -1
                    i = close + 1
                    continue
                }
            }
            i++
        }
        return -1
    }

    /**
     * Finds the `{` that opens a function body after the parameter list, skipping return type
     * annotations (including object shaped ones such as `: { ok: boolean }`).
     */
    private fun findBodyBrace(from: Int): Int {
        var i = from
        var guard = 0
        while (i < tokens.size && guard++ < 80) {
            when (tokens[i].text) {
                "{" -> return i
                ";", "=", ")", "]" -> return -1
                "<" -> {
                    val afterGeneric = skipTypeParameters(i)
                    if (afterGeneric > i) {
                        i = afterGeneric
                        continue
                    }
                }
                "(", "[" -> {
                    val close = bracketMatches[i]
                    if (close > 0) {
                        i = close + 1
                        continue
                    }
                }
                ":" -> {
                    val typeStart = i + 1
                    if (nextText(typeStart) == "{") {
                        val close = bracketMatches[typeStart]
                        if (close > 0) {
                            i = close + 1
                            continue
                        }
                    }
                }
            }
            i++
        }
        return -1
    }

    /** Locates the `=>` of an arrow function whose value expression starts at [from]. */
    private fun findArrow(from: Int): Int {
        val start = skipAsyncOperator(from)
        if (nextText(start) == "(") {
            val close = bracketMatches[start]
            if (close <= 0) return -1
            val afterParams = close + 1
            if (nextText(afterParams) == ":") return findArrowOperator(afterParams + 1)
            return if (nextText(afterParams) == "=>") afterParams else -1
        }
        if (isNameToken(start) && nextText(start + 1) == "=>") return start + 1
        if (nextText(start) == "<") {
            val afterGeneric = skipTypeParameters(start)
            if (afterGeneric > start) return findArrow(afterGeneric)
        }
        return -1
    }

    private fun findArrowOperator(from: Int): Int {
        var i = from
        var guard = 0
        while (i < tokens.size && guard++ < 60) {
            when (tokens[i].text) {
                "=>" -> return i
                ";", "=", ",", ")" -> return -1
                "<" -> {
                    val after = skipTypeParameters(i)
                    if (after > i) {
                        i = after
                        continue
                    }
                }
                "(", "[", "{" -> {
                    val close = bracketMatches[i]
                    if (close > 0) {
                        i = close + 1
                        continue
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun expressionEnd(from: Int): Int {
        if (from >= tokens.size) return tokens.lastIndex
        var i = from
        var guard = 0
        while (i < tokens.size && guard++ < 400) {
            val token = tokens[i]
            when (token.text) {
                "(", "[", "{" -> {
                    val close = bracketMatches[i]
                    if (close <= 0) return if (i > from) i - 1 else i
                    i = close + 1
                    continue
                }
                ";", ",", ")", "]", "}" -> return if (i > from) i - 1 else i
            }
            if (i > from && token.line > tokens[from].line && token.text in STATEMENT_STARTERS) {
                return i - 1
            }
            i++
        }
        return tokens.lastIndex
    }

    private companion object {
        val CLASS_MEMBER_MODIFIERS = setOf(
            "static", "async", "get", "set", "public", "private", "protected", "readonly", "abstract",
            "override", "declare", "*"
        )

        val STATEMENT_STARTERS = setOf(
            "const", "let", "var", "function", "class", "export", "import", "interface", "type",
            "enum", "return", "if", "for", "while", "switch", "try", "throw"
        )
    }
}

// ---------------------------------------------------------------------------------------
// Moved from js/FlowSource.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * A parsed JS/TS/JSX/TSX file, as the tokenizer tier sees it.
 *
 * Deliberately platform free: the tokenizer tier and its call resolver only need the file path,
 * its text, the parsed [JsFileOutline] and the detected language, so this type can be built both
 * from PSI (open editors) and from disk (imports).
 */
internal data class FlowSource(
    val filePath: String,
    val text: String,
    val outline: JsFileOutline,
    val languageKind: LanguageKind
)

// ---------------------------------------------------------------------------------------
// Moved from js/AnalysisContext.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * Per-run cache for parsed files, used by the tokenizer tier. Nothing is kept between runs.
 *
 * Deliberately platform free: in the IDE the [JsTsFlowExtractor] passes `project.basePath` for
 * project-relative paths; the headless [com.bisotun.flowextractor.TextFlowExtractor] passes
 * `null` so absolute fixture paths are preserved.
 */
internal class AnalysisContext(private val basePath: String?) {

    private val sources = HashMap<String, FlowSource?>()

    /** The file currently open in the editor is read through PSI so unsaved edits are respected. */
    fun sourceForFile(file: PsiFile?): FlowSource? {
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

    fun sourceForPath(path: String, hint: LanguageKind): FlowSource? {
        if (sources.containsKey(path)) return sources[path]
        val source = readFromDisk(path, hint)
        sources[path] = source
        return source
    }

    /**
     * Resolves only relative specifiers (`./x`, `../x/y`), adding the usual extensions and an
     * `index.*` fallback. Path aliases (`@/...`) are intentionally left unresolved.
     */
    fun resolveSpecifier(fromPath: String, specifier: String): String? {
        if (!specifier.startsWith(".")) return null
        val baseDirectory = File(fromPath).parentFile ?: return null
        val base = File(baseDirectory, specifier).toPath().normalize().toString()
        val candidates = mutableListOf<String>()
        candidates.add(base)
        EXTENSIONS.forEach { candidates.add("$base.$it") }
        EXTENSIONS.forEach { candidates.add(File(base, "index.$it").path) }
        return candidates.firstOrNull { File(it).isFile }
    }

    private fun readFromDisk(path: String, hint: LanguageKind): FlowSource? = try {
        val file = File(path)
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            null
        } else {
            buildSource(path, file.readText(), hint)
        }
    } catch (t: Throwable) {
        null
    }

    private fun buildSource(path: String, text: String, hint: LanguageKind): FlowSource {
        val byExtension = LanguageKind.fromExtension(File(path).extension)
        val languageKind = if (byExtension == LanguageKind.UNKNOWN) hint else byExtension
        return FlowSource(path, text, JsFileOutline.parse(text), languageKind)
    }

    private companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
        val EXTENSIONS = listOf("ts", "tsx", "js", "jsx", "mjs", "cjs")
    }
}

// ---------------------------------------------------------------------------------------
// Moved from js/JsBindingMatcher.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * Pure name matching used to relate an instance qualifier to an imported binding.
 *
 * A call such as `this.paymentService.processPayment()` names the *instance* (`paymentService`),
 * while the class is imported as `PaymentService` (or `PaymentGateway` for `gateway`). Matching is
 * only a filter: the resolver must still find the called member inside the matched target, so a
 * match can never fabricate a declaration.
 */
internal object JsBindingMatcher {

    /** Shorter names are too ambiguous to be matched structurally (`id`, `db`, `api`). */
    private const val MIN_FUZZY_LENGTH = 3

    private const val EXACT_SCORE = 100
    private const val PLURAL_SCORE = 90
    private const val AFFIX_SCORE = 70
    private const val CONTAINS_SCORE = 50

    /**
     * Relation between [qualifier] and [candidate] in `0..100`; `0` means "unrelated".
     *
     * Case, `_`, `-` and `$` are ignored, so `payment_service`, `paymentService` and
     * `PaymentService` all compare equal.
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
    fun bindingScore(qualifier: String, binding: JsImportBinding): Int {
        val imported = binding.importedName?.let { score(qualifier, it) } ?: 0
        return maxOf(score(qualifier, binding.localName), imported)
    }

    private fun normalize(value: String): String =
        value.filter { it.isLetterOrDigit() }.lowercase()

    private fun singular(value: String): String =
        if (value.length > MIN_FUZZY_LENGTH && value.endsWith("s")) value.dropLast(1) else value
}

// ---------------------------------------------------------------------------------------
// Moved from js/JsCallResolver.kt - part of the single JS/TS extraction strategy
// ---------------------------------------------------------------------------------------

/**
 * A call resolved to a declaration that really exists in [source].
 *
 * `ResolvedCall(flowSource, functionDef)` is the only value the tokenizer tier expands, so a
 * resolution can never point at invented source.
 */
internal data class ResolvedCall(val source: FlowSource, val definition: JsFunctionDef)

/**
 * Cross file call resolution for the tokenizer tier.
 *
 * Resolution order, most to least confident:
 *  1. a declaration in the same file (`this.notify()` and plain `notify()`);
 *  2. an exact import binding - namespace imports (`import * as api`) and class imports;
 *  3. an imported binding whose name relates to the instance qualifier
 *     (`paymentService` -> `PaymentService`, `gateway` -> `PaymentGateway`);
 *  4. any imported file that declares a class method (preferred) or module function with the
 *     called name, when exactly one target file qualifies.
 *
 * Every step returns a declaration looked up by name inside the target file; when nothing matches
 * the call stays unresolved instead of being guessed. [importedSource] performs the platform work
 * (specifier resolution plus reading/parsing the target file), which keeps this class free of PSI.
 */
internal class JsCallResolver {

    fun resolve(
        source: FlowSource,
        site: JsCallSite,
        importedSource: (FlowSource, String) -> FlowSource?
    ): ResolvedCall? {
        val qualifier = site.qualifier
        if (qualifier == null || qualifier == THIS) {
            val local = source.outline.functionNamed(site.name)
            if (local != null && local.startOffset != site.offset) return ResolvedCall(source, local)
            if (qualifier == THIS) return null
        }
        if (qualifier == null) {
            val binding = source.outline.importFor(site.name) ?: return null
            return resolveBinding(source, binding, importedSource)
        }
        return resolveMemberCall(source, site, qualifier, importedSource)
    }

    /** `service.processPayment()` / `this.paymentService.processPayment()`. */
    private fun resolveMemberCall(
        source: FlowSource,
        site: JsCallSite,
        qualifier: String,
        importedSource: (FlowSource, String) -> FlowSource?
    ): ResolvedCall? {
        localMember(source, qualifier, site.name)?.let { return it }

        val exact = source.outline.importFor(qualifier)
        if (exact != null) {
            resolveBindingMember(source, exact, site.name, importedSource)?.let { return it }
        }

        val fuzzyBindings = source.outline.imports
            .filter { it !== exact }
            .map { it to JsBindingMatcher.bindingScore(qualifier, it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        for ((binding, _) in fuzzyBindings) {
            resolveBindingMember(source, binding, site.name, importedSource)?.let { return it }
        }

        // Host globals (`console.log`, `document.querySelector`, ...) never point at an import.
        if (qualifier in GLOBAL_QUALIFIERS) return null
        return resolveByImportedDeclaration(source, qualifier, site.name, importedSource)
    }

    /** Class method named [memberName] declared in this file for an instance called [qualifier]. */
    private fun localMember(source: FlowSource, qualifier: String, memberName: String): ResolvedCall? {
        val outline = source.outline
        val bestType = outline.types
            .map { type -> type to JsBindingMatcher.score(qualifier, type.name) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
        if (bestType != null) {
            outline.methodIn(bestType, memberName)?.let { return ResolvedCall(source, it) }
        }
        val candidates = outline.types.mapNotNull { type -> outline.methodIn(type, memberName) }
            .distinctBy { it.startOffset }
        return if (candidates.size == 1) ResolvedCall(source, candidates.single()) else null
    }

    /** `import { Foo } from './foo'` used directly, e.g. `Foo.bar()` on a namespace-like module. */
    private fun resolveBinding(
        source: FlowSource,
        binding: JsImportBinding,
        importedSource: (FlowSource, String) -> FlowSource?
    ): ResolvedCall? {
        val targetSource = importedSource(source, binding.specifier) ?: return null
        val definition = when {
            binding.importedName != null -> targetSource.outline.functionNamed(binding.importedName)
            binding.isNamespace -> null
            else -> targetSource.outline.defaultExportName?.let { targetSource.outline.functionNamed(it) }
        } ?: return null
        return ResolvedCall(targetSource, definition)
    }

    /**
     * Resolves [memberName] inside the declaration [binding] points at: the exported class first
     * (its method, constructor or arrow field), then a module level declaration when the target has
     * no such class. A class that does not declare [memberName] keeps the call unresolved instead of
     * falling back to an unrelated module function.
     */
    private fun resolveBindingMember(
        source: FlowSource,
        binding: JsImportBinding,
        memberName: String,
        importedSource: (FlowSource, String) -> FlowSource?
    ): ResolvedCall? {
        val targetSource = importedSource(source, binding.specifier) ?: return null
        val target = targetSource.outline
        if (binding.isNamespace) {
            val definition = target.functionNamed(memberName) ?: return null
            return ResolvedCall(targetSource, definition)
        }
        val exportedName = binding.importedName ?: target.defaultExportName ?: binding.localName
        val type = target.typeNamed(exportedName) ?: findMatchingType(target, binding)
        if (type != null) {
            val method = target.methodIn(type, memberName) ?: return null
            return ResolvedCall(targetSource, method)
        }
        val definition = target.functionNamed(memberName) ?: return null
        return ResolvedCall(targetSource, definition)
    }

    /** Class whose name is the closest structural match for an aliased import. */
    private fun findMatchingType(outline: JsFileOutline, binding: JsImportBinding): JsTypeDef? {
        val names = listOfNotNull(binding.importedName, binding.localName)
        return outline.types
            .map { type -> type to names.maxOf { JsBindingMatcher.score(it, type.name) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Last resort: the qualifier has no name relation to any import, but exactly one imported file
     * declares a method (or module function) called [memberName]. Ambiguous matches stay unresolved.
     */
    private fun resolveByImportedDeclaration(
        source: FlowSource,
        qualifier: String,
        memberName: String,
        importedSource: (FlowSource, String) -> FlowSource?
    ): ResolvedCall? {
        val candidates = mutableListOf<Candidate>()
        for (binding in source.outline.imports.distinctBy { it.specifier }) {
            val targetSource = importedSource(source, binding.specifier) ?: continue
            val target = targetSource.outline
            for (type in target.types) {
                val definition = target.methodIn(type, memberName) ?: continue
                candidates.add(
                    Candidate(
                        call = ResolvedCall(targetSource, definition),
                        tier = MEMBER_TIER,
                        score = JsBindingMatcher.score(qualifier, type.name),
                        filePath = targetSource.filePath
                    )
                )
            }
            val definition = target.functionNamed(memberName) ?: continue
            candidates.add(
                Candidate(
                    call = ResolvedCall(targetSource, definition),
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

    private data class Candidate(val call: ResolvedCall, val tier: Int, val score: Int, val filePath: String)

    private companion object {
        const val THIS = "this"
        const val MEMBER_TIER = 2
        const val MODULE_TIER = 1

        val GLOBAL_QUALIFIERS = setOf(
            "console", "window", "document", "globalThis", "global", "Math", "JSON", "Object", "Array",
            "String", "Number", "Boolean", "Date", "Promise", "Reflect", "Symbol", "Error", "RegExp",
            "Map", "Set", "WeakMap", "WeakSet", "process", "module", "exports", "localStorage",
            "sessionStorage", "navigator", "location", "history", "fetch", "require"
        )
    }
}

/** Computes a 1-based, inclusive line range for a source offset span. */
internal fun jsLineRange(text: String, startOffset: Int, endOffset: Int): SourceRange {
    if (text.isEmpty()) return SourceRange.UNKNOWN
    val start = startOffset.coerceIn(0, text.length)
    val end = endOffset.coerceIn(start, text.length)
    var startLine = 1
    var endLine = 1
    for (index in 0 until end) {
        if (text[index] == '\n') {
            endLine++
            if (index < start) startLine++
        }
    }
    return SourceRange(startLine, endLine)
}

/** Strips [basePath] from a file path; returns [path] unchanged when it is not inside the base. */
internal fun jsRelativePath(path: String, basePath: String?): String {
    val base = basePath ?: return path
    return if (path.startsWith(base)) path.removePrefix(base).removePrefix("/") else path
}
