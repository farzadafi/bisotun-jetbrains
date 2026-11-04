package com.bisotun.language

import com.intellij.psi.PsiFile

/**
 * Resolves the [LanguageKind] of a file.
 *
 * The IntelliJ language id is authoritative when a real language plugin parses the file; otherwise
 * (for example an IntelliJ Community build without the JavaScript plugin, where JS files are just
 * plain text) the file extension is used. JSX/TSX share the JavaScript/TypeScript language id, so
 * the extension wins for those two.
 */
object LanguageKindDetector {

    fun detect(file: PsiFile?): LanguageKind {
        if (file == null) return LanguageKind.UNKNOWN
        val byLanguage = LanguageKind.fromLanguageId(safeLanguageId(file))
        val byExtension = LanguageKind.fromExtension(safeExtension(file))
        return merge(byLanguage, byExtension)
    }

    private fun merge(byLanguage: LanguageKind, byExtension: LanguageKind): LanguageKind = when {
        byExtension == LanguageKind.UNKNOWN -> byLanguage
        byLanguage == LanguageKind.UNKNOWN -> byExtension
        byExtension == LanguageKind.JSX || byExtension == LanguageKind.TSX -> byExtension
        byLanguage.isJsFamily && byExtension.isJsFamily -> byExtension
        else -> byLanguage
    }

    private fun safeLanguageId(file: PsiFile): String? = try {
        file.language.id
    } catch (t: Throwable) {
        null
    }

    private fun safeExtension(file: PsiFile): String? = try {
        file.virtualFile?.extension
    } catch (t: Throwable) {
        null
    }
}
