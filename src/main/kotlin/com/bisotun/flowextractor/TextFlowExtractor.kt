package com.bisotun.flowextractor

import com.bisotun.MarkdownFormatter
import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.domain.SourceRange
import com.bisotun.language.AnalysisContext
import com.bisotun.language.FlowSource
import com.bisotun.language.JsCallResolver
import com.bisotun.language.JsCallSite
import com.bisotun.language.JsFileOutline
import com.bisotun.language.JsFunctionDef
import com.bisotun.language.JsTypeDef
import com.bisotun.language.LanguageKind
import com.bisotun.language.ResolvedCall
import com.bisotun.language.jsLineRange
import com.bisotun.language.jsRelativePath

/**
 * Headless, platform-free flow extractor for JavaScript/TypeScript sources.
 *
 * It reuses the exact same tokenizer strategy the IDE plugin falls back to in IntelliJ IDEA
 * Community, but drives it from one file path + function name instead of a caret offset + PSI file.
 * This makes the plugin's JS/TS flow behaviour testable on the JVM without an IntelliJ fixture and
 * gives a machine readable entry point (`extract(...) -> String` markdown) alongside the structural
 * [extractFlow].
 *
 * No regex is used anywhere: all parsing comes from the tokenizer tier, all call resolution comes
 * from the same [JsCallResolver] the IDE path uses, and every node maps to a real source range.
 * Nothing that cannot be resolved is fabricated.
 *
 * @param basePath optional project root used only to shorten emitted `filePath`s. When null (the
 *   headless default) absolute paths are preserved so outputs are independent of any IDE project.
 */
class TextFlowExtractor(private val basePath: String? = null) {

    private val callResolver = JsCallResolver()

    /** Renders the flow of [functionName] inside [sourceText] as markdown. */
    fun extract(
        filePath: String,
        sourceText: String,
        functionName: String,
        maxDepth: Int = 4,
        includeModels: Boolean = true
    ): String =
        MarkdownFormatter().format(
            extractFlow(filePath, sourceText, functionName, maxDepth, includeModels),
            maxDepth,
            includeModels
        )

    /**
     * Builds the structural flow of [functionName] inside [sourceText].
     *
     * When [functionName] does not match any real declaration the returned [FlowResult] has an
     * unresolved root and an explanatory warning; nothing is fabricated.
     */
    fun extractFlow(
        filePath: String,
        sourceText: String,
        functionName: String,
        maxDepth: Int = 4,
        includeModels: Boolean = true
    ): FlowResult {
        val source = buildSource(filePath, sourceText)
        val root = source.outline.functionNamed(functionName)
        return if (root == null) {
            FlowResult(
                root = unresolvedRoot(source, functionName),
                nodes = emptyList(),
                models = emptyList(),
                warnings = listOf(NO_MATCH_WARNING)
            )
        } else {
            buildTokenizedFlow(source, root, maxDepth, includeModels)
        }
    }

    private fun buildSource(filePath: String, sourceText: String): FlowSource {
        val kind = LanguageKind.fromExtension(fileExtension(filePath))
        return FlowSource(filePath, sourceText, JsFileOutline.parse(sourceText), kind)
    }

    private fun fileExtension(path: String): String =
        path.substringAfterLast(".", "").substringBefore('?').lowercase()

    private fun buildTokenizedFlow(
        source: FlowSource,
        root: JsFunctionDef,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowResult {
        val context = AnalysisContext(basePath)
        val roots = mutableListOf<ReaderScope>()
        val visitedNodeIds = mutableSetOf<String>()
        val rootNode = nodeFor(source, root, ResolutionStatus.RESOLVED)
        visitedNodeIds.add(rootNode.id)

        // BFS over real scopes, mirroring buildTokenizedFlow in the IDE tokenizer tier.
        if (maxDepth > 0) {
            var frontier = listOf(ReaderScope(source, root))
            var depth = 1
            while (depth <= maxDepth && frontier.isNotEmpty()) {
                val next = mutableListOf<ReaderScope>()
                for (handle in frontier) {
                    val scope = handle.scope ?: continue
                    val sites = handle.source.outline.callSitesIn(scope)
                    for (site in sites) {
                        val resolved = resolveTokenizedCall(handle.source, site, context)
                        if (resolved == null) {
                            val node = unresolvedNode(handle.source, site)
                            if (visitedNodeIds.add(node.id)) roots.add(ReaderScope(handle.source, null, node))
                            continue
                        }
                        val definition = resolved.definition
                        if (definition.name == root.name && definition.startOffset == root.startOffset) {
                            continue
                        }
                        val node = nodeFor(resolved.source, definition, ResolutionStatus.RESOLVED)
                        if (!visitedNodeIds.add(node.id)) continue
                        roots.add(ReaderScope(resolved.source, definition))
                        if (depth < maxDepth) next.add(ReaderScope(resolved.source, definition))
                    }
                }
                frontier = next
                depth++
            }
        }

        val nodes = roots.filter { it.node == null }.map { nodeFor(it.source, it.scope!!, ResolutionStatus.RESOLVED) } +
            roots.filter { it.node != null }.map { it.node!! }
        val models = if (includeModels) collectModels(listOf(ReaderScope(source, root)) + roots, context) else emptyList()
        return FlowResult(
            root = rootNode,
            nodes = nodes,
            models = models,
            warnings = emptyList()
        )
    }


    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * One processed scope: [source] is where [scope] lives, and [node] carries an already built
     * [FlowNode] for unresolved call sites (scope == null) so they are preserved verbatim in the
     * output without squatting on a real declaration.
     */
    private data class ReaderScope(
        val source: FlowSource,
        val scope: JsFunctionDef?,
        val node: FlowNode? = null
    )

    private fun collectModels(roots: List<ReaderScope>, context: AnalysisContext): List<FlowNode> {
        val models = LinkedHashMap<String, FlowNode>()
        for (handle in roots) {
            val scope = handle.scope ?: continue
            for (name in handle.source.outline.identifiersIn(scope.startOffset, scope.endOffset)) {
                val localType = handle.source.outline.typeNamed(name)
                if (localType != null) {
                    addTokenizedModel(models, handle.source, localType)
                    continue
                }
                val binding = handle.source.outline.importFor(name) ?: continue
                val importedName = binding.importedName ?: continue
                val targetSource = importedSource(handle.source, binding.specifier, context) ?: continue
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
            filePath = jsRelativePath(source.filePath, basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = ResolutionStatus.RESOLVED,
            kind = NodeKind.MODEL,
            code = source.text.substring(start, end).trim()
        )
    }

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

    private fun nodeFor(source: FlowSource, scope: JsFunctionDef, status: ResolutionStatus): FlowNode {
        val start = scope.startOffset.coerceIn(0, source.text.length)
        val end = scope.endOffset.coerceIn(start, source.text.length)
        return FlowNode(
            id = "${source.filePath}:$start-$end",
            displayName = scope.displayName,
            filePath = jsRelativePath(source.filePath, basePath),
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
            filePath = jsRelativePath(source.filePath, basePath),
            range = jsLineRange(source.text, start, end),
            languageKind = source.languageKind,
            resolutionStatus = ResolutionStatus.UNRESOLVED,
            kind = NodeKind.UNKNOWN
        )
    }

    private fun unresolvedRoot(source: FlowSource, functionName: String): FlowNode = FlowNode(
        id = "${source.filePath}:<missing:$functionName>",
        displayName = "$functionName()",
        filePath = jsRelativePath(source.filePath, basePath),
        range = SourceRange.UNKNOWN,
        languageKind = source.languageKind,
        resolutionStatus = ResolutionStatus.UNRESOLVED,
        kind = NodeKind.UNKNOWN
    )

    private companion object {
        const val NO_MATCH_WARNING =
            "No declaration named as requested was found. Nothing was fabricated - no flow emitted."
    }
}

