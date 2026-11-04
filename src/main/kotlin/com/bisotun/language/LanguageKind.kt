package com.bisotun.language

/**
 * Languages this plugin can describe.
 *
 * Intentionally platform free (no PSI imports) so the domain model can depend on it. Detection
 * from a PSI file lives in [LanguageKindDetector].
 */
enum class LanguageKind(val displayName: String, val codeFence: String) {
    JAVA("Java", "java"),
    KOTLIN("Kotlin", "kotlin"),
    JS("JavaScript", "javascript"),
    JSX("JavaScript (JSX)", "javascript"),
    TS("TypeScript", "typescript"),
    TSX("TypeScript (TSX)", "typescript"),
    UNKNOWN("Unknown", "text");

    val isJsFamily: Boolean get() = this == JS || this == JSX || this == TS || this == TSX

    val isJavaFamily: Boolean get() = this == JAVA || this == KOTLIN

    companion object {
        fun fromExtension(extension: String?): LanguageKind = when (extension?.lowercase()) {
            "java" -> JAVA
            "kt", "kts" -> KOTLIN
            "js", "mjs", "cjs" -> JS
            "jsx" -> JSX
            "ts", "mts", "cts" -> TS
            "tsx" -> TSX
            else -> UNKNOWN
        }

        fun fromLanguageId(languageId: String?): LanguageKind {
            val id = languageId?.lowercase()?.trim() ?: return UNKNOWN
            return when {
                id == "java" -> JAVA
                id == "kotlin" -> KOTLIN
                id.contains("typescript") && id.contains("jsx") -> TSX
                id.contains("typescript") -> TS
                id.contains("javascript") && id.contains("jsx") -> JSX
                id.contains("javascript") || id.contains("ecmascript") -> JS
                id == "ts" || id == "tsx" || id == "js" || id == "jsx" -> fromExtension(id)
                else -> UNKNOWN
            }
        }
    }
}
