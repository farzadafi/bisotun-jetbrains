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
        val sb = StringBuilder()

        sb.appendLine("# Flow Extracted by Bisotun")
        sb.appendLine()
        sb.appendLine("**Configuration:** maxDepth=${if (nodes.isNotEmpty()) ">0" else "0"}, includeModels=$includeModels")
        sb.appendLine()

        // Execution Flow Summary
        sb.appendLine("## Execution Flow Summary")
        sb.appendLine()
        
        val allMethods = mutableListOf<PsiMethod>()
        allMethods.add(rootMethod)
        nodes.filterIsInstance<PsiMethod>().forEach { allMethods.add(it) }
        
        // Deduplicate methods by qualified signature
        val uniqueMethods = mutableMapOf<String, PsiMethod>()
        allMethods.forEach { method ->
            val signature = getMethodSignature(method)
            uniqueMethods[signature] = method
        }
        
        uniqueMethods.values.forEachIndexed { index, method ->
            sb.appendLine("${index + 1}. `${getMethodSignature(method)}`")
        }
        
        sb.appendLine()

        // Method Source Code & Implementations
        if (uniqueMethods.isNotEmpty()) {
            sb.appendLine("## Method Source Code & Implementations")
            sb.appendLine()
            
            uniqueMethods.values.forEachIndexed { index, method ->
                sb.appendLine("### ${index + 1}. `${getMethodSignature(method)}`")
                
                val fileInfo = getFileInfo(method)
                if (fileInfo.isNotEmpty()) {
                    sb.appendLine("- File: `$fileInfo`")
                }
                
                val (startLine, endLine) = getLineRange(method)
                sb.appendLine("- Lines: $startLine-$endLine")
                sb.appendLine()
                
                val code = method.text?.trim() ?: ""
                val lang = determineLanguage(method)
                sb.appendLine("```$lang")
                sb.appendLine(code)
                sb.appendLine("```")
                sb.appendLine()
            }
        }

        // Related Data Models & Entities (only when includeModels = true)
        if (includeModels && models.isNotEmpty()) {
            sb.appendLine("## Related Data Models & Entities")
            sb.appendLine()
            
            // Deduplicate models
            val uniqueModels = mutableMapOf<String, PsiElement>()
            models.forEach { model ->
                val name = when (model) {
                    is PsiClass -> model.qualifiedName ?: model.name ?: "Unknown"
                    else -> model.toString()
                }
                uniqueModels[name] = model
            }
            
            uniqueModels.values.forEachIndexed { index, model ->
                val modelName = when (model) {
                    is PsiClass -> model.qualifiedName ?: model.name ?: "UnknownClass"
                    else -> model.toString()
                }
                
                sb.appendLine("### ${index + 1}. `$modelName`")
                
                val fileInfo = getFileInfo(model)
                if (fileInfo.isNotEmpty()) {
                    sb.appendLine("- File: `$fileInfo`")
                }
                
                val (startLine, endLine) = getLineRange(model)
                sb.appendLine("- Lines: $startLine-$endLine")
                sb.appendLine()
                
                val code = model.text?.trim() ?: ""
                val lang = determineLanguage(model)
                sb.appendLine("```$lang")
                sb.appendLine(code)
                sb.appendLine("```")
                sb.appendLine()
            }
        }

        return sb.toString()
    }

    private fun getMethodSignature(method: PsiMethod): String {
        val className = method.containingClass?.qualifiedName ?: method.containingClass?.name ?: "Unknown"
        val methodName = method.name ?: "Unknown"
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type?.presentableText ?: "?"} ${param.name ?: ""}"
        }
        return "$className.$methodName($params)"
    }

    private fun determineLanguage(element: PsiElement): String {
        val fileName = element.containingFile?.name?.lowercase() ?: ""
        return when {
            fileName.endsWith(".kt") -> "kotlin"
            fileName.endsWith(".java") -> "java"
            fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> "typescript"
            fileName.endsWith(".js") || fileName.endsWith(".jsx") -> "javascript"
            else -> "java" // default
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
