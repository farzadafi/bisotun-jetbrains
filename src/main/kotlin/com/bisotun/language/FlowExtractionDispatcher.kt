package com.bisotun.language

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFile

/**
 * Routes an extraction request to the [LanguageFlowExtractor] that owns the file.
 *
 * The decision is taken from the file itself ([LanguageKindDetector] merges the PSI language id with
 * the file extension), never from the calling action or class name heuristics. This is also what
 * makes JS/TSX work in IntelliJ IDEA Community: those files are plain text there, so detection falls
 * back to the extension and the JS/TS strategy decides whether the caret is usable.
 *
 * Java and Go extractors are not named here. They are contributed by optional content modules, which
 * the IDE loads only when the Java or Go plugin is installed, so this class never references those
 * PSI types.
 */
class FlowExtractionDispatcher(private val project: Project) {

    private val log = Logger.getInstance(FlowExtractionDispatcher::class.java)

    /** Strategies are cheap and stateless apart from the project, so one instance per dispatch is enough. */
    private val extractors: List<LanguageFlowExtractor> = buildList {
        EXTRACTOR_EP.extensionList.forEach { provider ->
            add(provider.create(project))
        }
        add(JsTsFlowExtractor(project))
        add(PythonFlowExtractor(project))
        add(CppFlowExtractor(project))
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
            ApplicationManager.getApplication().runReadAction(
                Computable { extractor.extractFlow(file, editor, maxDepth, includeModels) }
            )
        } catch (e: ProcessCanceledException) {
            // Let the platform cancel processing (e.g. indexing or modal progress) instead of
            // swallowing it; do NOT convert it into a generic "could not analyze" failure.
            throw e
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
        ApplicationManager.getApplication().runReadAction(
            Computable { LanguageKindDetector.detect(file) }
        )
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (t: Throwable) {
        log.debug("Language detection failed", t)
        LanguageKind.UNKNOWN
    }

    fun extractorFor(languageKind: LanguageKind): LanguageFlowExtractor? =
        extractors.firstOrNull { it.supports(languageKind) }

    private fun unsupportedLanguageMessage(languageKind: LanguageKind): String = when (languageKind) {
        LanguageKind.KOTLIN -> KOTLIN_PLUGIN_REQUIRED_MESSAGE
        LanguageKind.GO -> GO_PLUGIN_REQUIRED_MESSAGE
        LanguageKind.UNKNOWN -> "This file type is not supported by Bisotun."
        else -> "No flow extractor is registered for ${languageKind.displayName}."
    }

    private companion object {
        val EXTRACTOR_EP = ExtensionPointName.create<LanguageFlowExtractorProvider>(
            "com.bisotun.flowextractor.languageFlowExtractor"
        )
    }
}
