package com.bisotun

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

interface LanguageStrategy {
    val languageId: String

    fun supports(languageId: String): Boolean

    fun extractTypeCandidates(code: String): List<String>

    fun findEnclosingScope(
        file: PsiFile,
        position: Int
    ): EnclosingScope?

    fun resolveModelDefinition(
        file: PsiFile,
        typeName: String,
        methodRange: PsiElement
    ): ModelDefinition?

    fun resolveImplementationLocation(
        file: PsiFile,
        position: Int
    ): ImplementationLocation?

    fun resolveFallbackCalls(
        file: PsiFile,
        methodRange: PsiElement
    ): List<FallbackCall>
}

data class EnclosingScope(
    val className: String,
    val methodName: String,
    val startLine: Int,
    val endLine: Int
)

data class ModelDefinition(
    val name: String,
    val filePath: String,
    val code: String
)

data class ImplementationLocation(
    val psiElement: PsiElement,
    val startLine: Int,
    val endLine: Int
)

data class FallbackCall(
    val psiElement: PsiElement,
    val name: String,
    val detail: String? = null
)
