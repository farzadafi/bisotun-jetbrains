package com.bisotun.language

import com.bisotun.FlowCrawler
import com.bisotun.ModelExtractor
import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil

/**
 * Java strategy: a thin adapter over the production Java crawler ([FlowCrawler] + [ModelExtractor]),
 * which keeps using PSI references, resolve and `DefinitionsScopedSearch` exactly as before.
 *
 * Only the mapping onto the language agnostic domain model lives here, so consolidating the action
 * pipeline cannot change Java behaviour: the root is still the enclosing [PsiMethod] of the caret and
 * the traversal is still delegated unchanged.
 */
class JavaFlowExtractor(private val project: Project) : LanguageFlowExtractor {

    private val log = Logger.getInstance(JavaFlowExtractor::class.java)

    override val supportedLanguages: Set<LanguageKind> = setOf(LanguageKind.JAVA, LanguageKind.KOTLIN)

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = LanguageKindDetector.detect(file)
        val rootMethod = rootMethodAt(file, editor)
            ?: return FlowExtractionOutcome.failure(languageKind, missingCaretMessage(languageKind))

        val result = try {
            buildFlow(rootMethod, maxDepth, includeModels)
        } catch (t: Throwable) {
            log.warn("Java flow crawling failed for '${rootMethod.name}'", t)
            return FlowExtractionOutcome.failure(
                languageKind,
                "Flow extraction failed: ${t.message ?: "unknown error"}"
            )
        }
        return FlowExtractionOutcome(result, languageKind)
    }

    /** Caret -> enclosing `PsiMethod`; the only validation Java needs. */
    private fun rootMethodAt(file: PsiFile, editor: Editor): PsiMethod? {
        val offset = editor.caretModel.offset.coerceIn(0, maxOf(file.textLength - 1, 0))
        val leaf = file.findElementAt(offset) ?: return null
        return PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java)
    }

    private fun missingCaretMessage(languageKind: LanguageKind): String = when (languageKind) {
        LanguageKind.KOTLIN ->
            "Kotlin PSI is not bundled with this plugin, so Kotlin flow extraction is not supported."
        else -> "Please place the caret inside a method to extract the flow."
    }

    private fun buildFlow(rootMethod: PsiMethod, maxDepth: Int, includeModels: Boolean): FlowResult {
        val crawled = FlowCrawler(project)
            .crawl(rootMethod, maxDepth = maxDepth, useFallback = true)
            .filterIsInstance<PsiMethod>()
        val nodes = crawled.map { PsiFlowNodes.fromMethod(project, it) }
        val models = if (includeModels) extractModelNodes(listOf(rootMethod) + crawled) else emptyList()
        return FlowResult(
            root = PsiFlowNodes.fromMethod(project, rootMethod),
            nodes = nodes,
            models = models,
            warnings = emptyList()
        )
    }

    private fun extractModelNodes(methods: List<PsiMethod>): List<FlowNode> {
        val extractor = ModelExtractor(project)
        return try {
            methods
                .flatMap { extractor.extractModels(it) }
                .distinctBy { PsiFlowNodes.nodeId(it) }
                .map { modelNode(it) }
        } catch (t: Throwable) {
            log.warn("Java model extraction failed", t)
            emptyList()
        }
    }

    private fun modelNode(element: PsiElement) = PsiFlowNodes.fromElement(
        project,
        element,
        LanguageKind.JAVA,
        NodeKind.MODEL,
        ResolutionStatus.RESOLVED,
        modelName(element)
    )

    private fun modelName(element: PsiElement): String {
        val psiClass = element as? PsiClass
        return psiClass?.qualifiedName ?: psiClass?.name ?: "model"
    }
}
