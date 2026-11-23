package com.bisotun.language

import com.intellij.openapi.project.Project

/**
 * Creates one [LanguageFlowExtractor] from an optional content module.
 *
 * The main plugin does not name Java or Go extractor classes. Each content module contributes an
 * implementation, and the IDE omits that contribution when the required language plugin is absent.
 */
interface LanguageFlowExtractorProvider {
    fun create(project: Project): LanguageFlowExtractor
}
