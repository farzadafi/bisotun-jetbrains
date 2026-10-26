package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod

class CopyShallowFlowWithoutModelAction : ExtractFlowActionBase(
    maxDepth = 1,
    includeModels = false,
    actionText = "Copy Shallow Flow (No Models)",
    actionDescription = "Extract shallow execution call flow without data models to clipboard",
    shortcut = "ctrl alt shift M"
) {
    override fun performAction(project: Project, psiFile: PsiFile, editor: Editor) {
        // Check if caret is on a method/function
        val hasFunction = ReadAction.compute<Boolean, Exception> {
            isCaretOnMethod(psiFile, editor)
        }
        
        if (!hasFunction) {
            showWarningNotification(project, "Please place the caret inside a method or function to extract the flow.")
            return
        }

        val (nodes, models) = crawlFromRoot(project, psiFile, editor, maxDepth = this.maxDepth, useFallback = true)

        val rootFunction = getRootFunction(psiFile, editor)
        if (rootFunction == null) {
            showWarningNotification(project, "No function found at cursor position.")
            return
        }

        val formatter = MarkdownFormatter(project)
        val markdown = if (rootFunction is PsiMethod) {
            formatter.format(rootFunction, nodes, models, includeModels = false)
        } else {
            // JavaScript/TypeScript path
            formatJsFlow(rootFunction, nodes, maxDepth = maxDepth)
        }

        copyToClip(markdown)
        showMessage(project, "Shallow flow with ${nodes.size} calls (no models) copied to clipboard", "Copy Shallow Flow (No Models)")
    }
    
    private fun formatJsFlow(
        rootFunction: com.intellij.psi.PsiElement,
        nodes: List<com.intellij.psi.PsiElement>,
        maxDepth: Int
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("# JavaScript/TypeScript Flow Extracted by Bisotun")
        sb.appendLine()
        sb.appendLine("**Configuration:** maxDepth=$maxDepth, includeModels=false")
        sb.appendLine()
        
        // Function signature
        val functionName = MultiLanguageFunctionDetector.getFunctionSignature(rootFunction)
        sb.appendLine("## Root Function")
        sb.appendLine("### `$functionName`")
        sb.appendLine()
        
        // Function source code
        val code = rootFunction.text?.trim() ?: ""
        val lang = if (MultiLanguageFunctionDetector.isJavaScriptOrTypeScriptFile(rootFunction.containingFile)) {
            val fileName = rootFunction.containingFile?.virtualFile?.name?.lowercase() ?: ""
            when {
                fileName.endsWith(".ts") || fileName.endsWith(".tsx") -> "typescript"
                else -> "javascript"
            }
        } else {
            "javascript"
        }
        
        sb.appendLine("```$lang")
        sb.appendLine(code)
        sb.appendLine("```")
        sb.appendLine()
        
        // Call expressions found
        if (nodes.isNotEmpty()) {
            sb.appendLine("## Direct Call Expressions")
            sb.appendLine()
            nodes.take(10).forEachIndexed { index, node ->
                sb.appendLine("${index + 1}. Call expression in function")
            }
            if (nodes.size > 10) {
                sb.appendLine("... and ${nodes.size - 10} more calls")
            }
            sb.appendLine()
        }
        
        // Note: Models section omitted because includeModels = false
        
        return sb.toString()
    }
}
