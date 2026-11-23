package com.bisotun.java

import com.bisotun.language.LanguageFlowExtractor
import com.bisotun.language.LanguageFlowExtractorProvider
import com.intellij.openapi.project.Project

/** Registered by the `bisotun-java` content module when `com.intellij.modules.java` is available. */
class JavaFlowExtractorProvider : LanguageFlowExtractorProvider {
    override fun create(project: Project): LanguageFlowExtractor = JavaFlowExtractor(project)
}
