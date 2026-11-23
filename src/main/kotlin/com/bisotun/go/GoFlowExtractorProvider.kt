package com.bisotun.go

import com.bisotun.language.LanguageFlowExtractor
import com.bisotun.language.LanguageFlowExtractorProvider
import com.intellij.openapi.project.Project

/** Registered by the `bisotun-go` content module when `org.jetbrains.plugins.go` is installed. */
class GoFlowExtractorProvider : LanguageFlowExtractorProvider {
    override fun create(project: Project): LanguageFlowExtractor = GoFlowExtractor(project)
}
