package com.bisotun.kotlin

import com.bisotun.language.LanguageFlowExtractor
import com.bisotun.language.LanguageFlowExtractorProvider
import com.intellij.openapi.project.Project

/**
 * Registered by the `bisotun-kotlin` content module when the JetBrains Kotlin plugin is available.
 *
 * Keeping the class in the content module means an IDE without Kotlin never loads any Kotlin PSI
 * class from this plugin.
 */
class KotlinFlowExtractorProvider : LanguageFlowExtractorProvider {
    override fun create(project: Project): LanguageFlowExtractor = KotlinFlowExtractor(project)
}
