package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod

class CopyDeepFlowAction : ExtractFlowActionBase(
    maxDepth = 4,
    includeModels = true,
    actionText = "Copy Deep Flow",
    actionDescription = "Extract deep execution call flow and data models to clipboard",
    shortcut = "ctrl alt shift D"
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
            formatter.format(rootFunction, nodes, models, includeModels = true)
        } else {
            // JavaScript/TypeScript path
            formatJsFlow(rootFunction, nodes, models, includeModels = true, maxDepth = maxDepth)
        }

        copyToClip(markdown)
        showMessage(project, "Deep flow with ${nodes.size} calls and ${models.size} models copied to clipboard", "Copy Deep Flow")
    }
    
    private fun formatJsFlow(
        rootFunction: com.intellij.psi.PsiElement,
        nodes: List<com.intellij.psi.PsiElement>,
        models: List<com.intellij.psi.PsiElement>,
        includeModels: Boolean,
        maxDepth: Int
    ): String {
        val sb = StringBuilder()
        
        sb.appendLine("# JavaScript/TypeScript Flow Extracted by Bisotun")
        sb.appendLine()
        sb.appendLine("**Configuration:** maxDepth=$maxDepth, includeModels=$includeModels")
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
            sb.appendLine("## Call Expressions Found")
            sb.appendLine()
            nodes.forEachIndexed { index, node ->
                // Simple representation for now
                sb.appendLine("${index + 1}. Call expression in function")
            }
            sb.appendLine()
        }
        
        // Models/Type references
        if (includeModels && models.isNotEmpty()) {
            sb.appendLine("## Related Types & Interfaces")
            sb.appendLine()
            models.forEachIndexed { index, model ->
                sb.appendLine("### ${index + 1}. Type reference")
                sb.appendLine()
                val modelCode = model.text?.trim() ?: ""
                if (modelCode.isNotEmpty()) {
                    sb.appendLine("```typescript")
                    sb.appendLine(modelCode)
                    sb.appendLine("```")
                    sb.appendLine()
                }
            }
        }
        
        return sb.toString()
    }
}
