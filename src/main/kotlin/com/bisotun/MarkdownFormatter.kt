package com.bisotun

import com.intellij.openapi.project.Project
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiDocumentManager
import kotlin.math.roundToLong

class MarkdownFormatter(private val project: Project) {

    fun format(
        rootMethod: PsiMethod,
        nodes: List<PsiElement>,
        models: List<PsiElement>,
        includeModels: Boolean = true
    ): String {
        val q = '`'
        val sb = StringBuilder()

        sb.appendLine("# Context Flow Extracted by Bisotun")
        sb.appendLine("- Extracted Nodes: ${nodes.size}")
        sb.appendLine("- Extracted Models: ${if (includeModels) models.size else 0}")

        val totalChars =
            nodes.sumOf { it.textLength } +
            (if (includeModels) models.sumOf { it.textLength } else 0)
        val estimatedTokens = (totalChars / 4.0).roundToLong()
        sb.appendLine("- Estimated Tokens: ~$estimatedTokens")
        sb.appendLine()

        // Root Method
        sb.appendLine("## Root Method")
        appendElementBlock(sb, rootMethod, q, includeModels)

        // Execution Dependencies
        if (nodes.isNotEmpty()) {
            sb.appendLine("## Execution Dependencies")
            for (node in nodes) {
                appendElementBlock(sb, node, q, includeModels)
            }
        }

        // Related Models / DTOs - only if includeModels is true
        if (includeModels && models.isNotEmpty()) {
            sb.appendLine("## Related Data Models (DTOs / Projections / Records)")
            for (model in models) {
                appendElementBlock(sb, model, q, includeModels)
            }
        }

        return sb.toString()
    }

    private fun appendElementBlock(sb: StringBuilder, element: PsiElement, q: Char, includeModels: Boolean) {
        val title = getElementTitle(element)
        val fileInfo = getFileInfo(element)
        val (startLine, endLine) = getLineRange(element)

        sb.appendLine("### $q$title$q")
        if (fileInfo.isNotEmpty()) {
            sb.appendLine("- File: $q$fileInfo$q")
        }
        sb.appendLine("- Lines: $startLine-$endLine")
        sb.appendLine()

        val text = element.text?.trim() ?: ""
        val lang = if (element.containingFile?.name?.endsWith(".kt") == true) "kotlin" else "java"
        sb.appendLine("$q$q$q$lang")
        sb.appendLine(text)
        sb.appendLine("$q$q$q")
        sb.appendLine()
    }

    private fun getElementTitle(element: PsiElement): String {
        return when (element) {
            is PsiMethod -> {
                val clsName = element.containingClass?.qualifiedName ?: element.containingClass?.name ?: "Unknown"
                "$clsName#${element.name}"
            }
            is PsiClass -> element.qualifiedName ?: element.name ?: "UnknownClass"
            else -> element.toString()
        }
    }

    private fun getFileInfo(element: PsiElement): String {
        val file = element.containingFile ?: return ""
        val vf = file.virtualFile ?: return file.name
        val projectBase = project.basePath
        return if (projectBase != null && vf.path.startsWith(projectBase)) {
            vf.path.removePrefix(projectBase).removePrefix("/")
        } else {
            vf.name
        }
    }

    private fun getLineRange(element: PsiElement): Pair<Int, Int> {
        val psiFile = element.containingFile ?: return Pair(1, 1)
        val document: Document? = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        if (document == null) return Pair(1, 1)

        val docLen = document.textLength
        if (docLen == 0) return Pair(1, 1)

        val range = element.textRange
        val safeStart = range.startOffset.coerceIn(0, docLen)
        val safeEnd = range.endOffset.coerceIn(0, docLen)

        val startLine = document.getLineNumber(safeStart) + 1
        val endLine = document.getLineNumber(safeEnd) + 1
        return Pair(startLine, endLine)
    }
}