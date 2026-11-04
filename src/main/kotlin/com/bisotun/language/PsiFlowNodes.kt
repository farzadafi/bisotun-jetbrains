package com.bisotun.language

import com.bisotun.domain.FlowNode
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.domain.SourceRange
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement

/**
 * Converts real PSI elements into domain [FlowNode]s.
 *
 * Every node maps to an existing element of an existing file - nothing is synthesised.
 */
object PsiFlowNodes {

    private const val UNKNOWN_FILE = "<unknown file>"

    fun fromMethod(
        project: Project,
        method: PsiMethod,
        languageKind: LanguageKind = LanguageKind.JAVA,
        status: ResolutionStatus = ResolutionStatus.RESOLVED
    ): FlowNode {
        val parameters = method.parameterList.parameters.joinToString(", ") { parameter ->
            listOfNotNull(parameter.type.presentableText, parameter.name).joinToString(" ")
        }
        val owner = method.containingClass?.qualifiedName ?: method.containingClass?.name
        val signature = if (owner != null) "$owner.${method.name}($parameters)" else "${method.name}($parameters)"
        return fromElement(project, method, languageKind, NodeKind.METHOD, status, signature)
    }

    fun fromElement(
        project: Project,
        element: PsiElement,
        languageKind: LanguageKind,
        kind: NodeKind,
        status: ResolutionStatus,
        displayName: String? = null
    ): FlowNode = FlowNode(
        id = nodeId(element),
        displayName = displayName ?: defaultDisplayName(element),
        filePath = relativePath(project, element.containingFile),
        range = lineRange(project, element),
        languageKind = languageKind,
        resolutionStatus = status,
        kind = kind,
        code = element.text?.trim().orEmpty()
    )

    fun nodeId(element: PsiElement): String {
        val file = element.containingFile
        val path = file?.virtualFile?.path ?: file?.name ?: UNKNOWN_FILE
        val range = element.textRange
        return "$path:${range.startOffset}-${range.endOffset}"
    }

    fun lineRange(project: Project, element: PsiElement): SourceRange {
        val file = element.containingFile ?: return SourceRange.UNKNOWN
        val document = try {
            PsiDocumentManager.getInstance(project).getDocument(file)
        } catch (t: Throwable) {
            null
        } ?: return SourceRange.UNKNOWN
        if (document.textLength == 0) return SourceRange.UNKNOWN
        val range = element.textRange
        val start = range.startOffset.coerceIn(0, document.textLength)
        val end = range.endOffset.coerceIn(0, document.textLength)
        return SourceRange(document.getLineNumber(start) + 1, document.getLineNumber(end) + 1)
    }

    fun relativePath(project: Project, file: PsiFile?): String {
        val path = file?.virtualFile?.path ?: return file?.name ?: UNKNOWN_FILE
        val basePath = project.basePath
        return if (basePath != null && path.startsWith(basePath)) {
            path.removePrefix(basePath).removePrefix("/")
        } else {
            path
        }
    }

    /**
     * Builds `name(param, param)` from the element text when a parameter list can be found; falls back
     * to the plain name. Purely textual reading of the *element's own* text, no file scanning.
     */
    private fun defaultDisplayName(element: PsiElement): String {
        val name = (element as? PsiNamedElement)?.name
            ?: enclosingVariableName(element)
            ?: "anonymous"
        val text = element.text ?: return name
        val open = text.indexOf('(')
        if (open < 0) return name
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        val parameters = text.substring(open + 1, index).trim()
                        return if (parameters.isEmpty()) "$name()" else "$name($parameters)"
                    }
                }
                '{', '=', ';' -> return name
            }
        }
        return name
    }

    private fun enclosingVariableName(element: PsiElement): String? {
        var current: PsiElement? = element.parent
        while (current != null && current !is PsiFile) {
            val owner = current as? PsiNamedElement
            val ownerName = owner?.name
            if (!ownerName.isNullOrBlank()) return ownerName
            current = current.parent
        }
        return null
    }
}
