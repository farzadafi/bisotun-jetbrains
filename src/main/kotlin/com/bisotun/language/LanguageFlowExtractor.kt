package com.bisotun.language

import com.bisotun.domain.FlowResult
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/**
 * Result of a single extraction run.
 *
 * [result] is null when nothing could be extracted; [message] then explains why (for example
 * "the caret is not inside a function"). Both fields are plain data, so the outcome can safely be
 * handed to the action after the read action that produced it has ended.
 */
data class FlowExtractionOutcome(
    val result: FlowResult?,
    val languageKind: LanguageKind,
    val message: String? = null
) {
    val isSuccess: Boolean get() = result != null

    companion object {
        fun failure(languageKind: LanguageKind, message: String): FlowExtractionOutcome =
            FlowExtractionOutcome(result = null, languageKind = languageKind, message = message)
    }
}

/**
 * Strategy for one language family.
 *
 * A single [extractFlow] entry point replaces the previous two phase contract
 * (`resolveRootFromCaret` + `buildFlow`): strategies receive the editor and file exactly as the
 * action sees them and decide themselves how to validate the caret. That matters for JavaScript and
 * TypeScript, whose PSI is *not* available in IntelliJ IDEA Community (those files arrive as plain
 * text), so no strategy may assume a [com.intellij.psi.PsiMethod] exists.
 *
 * Implementations must never fabricate PSI elements: every produced
 * [com.bisotun.domain.FlowNode] maps to a real source range, and anything that cannot be resolved is
 * reported as [com.bisotun.domain.ResolutionStatus.UNRESOLVED].
 */
interface LanguageFlowExtractor {

    val supportedLanguages: Set<LanguageKind>

    fun supports(languageKind: LanguageKind): Boolean = languageKind in supportedLanguages

    /**
     * Extracts the flow of the function/component/block that contains the caret.
     *
     * Called inside a read action. When the caret is not inside any meaningful scope the
     * implementation returns [FlowExtractionOutcome.failure] instead of guessing a root.
     */
    fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome
}
