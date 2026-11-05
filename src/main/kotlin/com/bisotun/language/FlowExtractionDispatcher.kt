package com.bisotun.language

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Routes an extraction request to the [LanguageFlowExtractor] that owns the file.
 *
 * The decision is taken from the file itself ([LanguageKindDetector] merges the PSI language id with
 * the file extension), never from the calling action or class name heuristics. This is also what
 * makes JS/TSX work in IntelliJ IDEA Community: those files are plain text there, so detection falls
 * back to the extension and the JS/TS strategy - not a `PsiMethod` check - decides whether the caret
 * is usable.
 */
class FlowExtractionDispatcher(private val project: Project) {

    private val log = Logger.getInstance(FlowExtractionDispatcher::class.java)

    /** Strategies are cheap and stateless apart from the project, so one instance per dispatch is enough. */
    private val extractors: List<LanguageFlowExtractor> = buildList {
        try {
            // Check if Java PSI is available in the current IDE runtime
            Class.forName("com.intellij.psi.PsiMethod", false, FlowExtractionDispatcher::class.java.classLoader)
            add(JavaFlowExtractor(project))
        } catch (_: Throwable) {
            log.info("Java module not detected; Java extraction disabled.")
        }
        add(JsTsFlowExtractor(project))
        add(PythonFlowExtractor(project))
    }

    fun extract(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = detectLanguage(file)
        val extractor = extractorFor(languageKind)
            ?: return FlowExtractionOutcome.failure(languageKind, unsupportedLanguageMessage(languageKind))

        return try {
            ReadAction.compute<FlowExtractionOutcome, Throwable> {
                extractor.extractFlow(file, editor, maxDepth, includeModels)
            }
        } catch (t: Throwable) {
            log.warn("${languageKind.displayName} flow extraction failed", t)
            FlowExtractionOutcome.failure(
                languageKind,
                "Could not analyze this file: ${t.message ?: "unknown error"}"
            )
        }
    }

    /** Language detection never throws; unknown metadata simply degrades to [LanguageKind.UNKNOWN]. */
    fun detectLanguage(file: PsiFile): LanguageKind = try {
        ReadAction.compute<LanguageKind, Throwable> { LanguageKindDetector.detect(file) }
    } catch (t: Throwable) {
        log.debug("Language detection failed", t)
        LanguageKind.UNKNOWN
    }

    fun extractorFor(languageKind: LanguageKind): LanguageFlowExtractor? =
        extractors.firstOrNull { it.supports(languageKind) }

    private fun unsupportedLanguageMessage(languageKind: LanguageKind): String = when (languageKind) {
        LanguageKind.KOTLIN ->
            "Kotlin PSI is not bundled with this plugin, so Kotlin flow extraction is not supported."
        LanguageKind.UNKNOWN -> "This file type is not supported by Bisotun."
        else -> "No flow extractor is registered for ${languageKind.displayName}."
    }
}
