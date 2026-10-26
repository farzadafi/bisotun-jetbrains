package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod

class CopyMethodOnlyAction : ExtractFlowActionBase(
    maxDepth = 0,
    includeModels = false,
    actionText = "Copy Method Only",
    actionDescription = "Extract only the current method/code at cursor position",
    shortcut = "ctrl alt shift C"
) {
    override fun performAction(project: Project, psiFile: PsiFile, editor: Editor) {
        // Check if caret is on a method/function
        val hasFunction = ReadAction.compute<Boolean, Exception> {
            isCaretOnMethod(psiFile, editor)
        }
        
        if (!hasFunction) {
            showWarningNotification(project, "Please place the caret inside a method or function to extract it.")
            return
        }

        // For maxDepth=0, we only get the root function without crawling
        val rootFunction = getRootFunction(psiFile, editor)
        if (rootFunction == null) {
            showWarningNotification(project, "No function found at cursor position.")
            return
        }

        val formatter = MarkdownFormatter(project)
        val markdown = if (rootFunction is PsiMethod) {
            formatter.format(rootFunction, emptyList(), emptyList(), includeModels = false)
        } else {
            // JavaScript/TypeScript path
            formatJsFunctionOnly(rootFunction)
        }

        copyToClip(markdown)
        showMessage(project, "Function `${MultiLanguageFunctionDetector.getFunctionName(rootFunction)}` copied to clipboard", "Copy Method Only")
    }
    
    private fun formatJsFunctionOnly(rootFunction: com.intellij.psi.PsiElement): String {
        val sb = StringBuilder()
        
        sb.appendLine("# Function Extracted by Bisotun")
        sb.appendLine()
        sb.appendLine("**Configuration:** maxDepth=0, includeModels=false")
        sb.appendLine()
        
        // Function signature
        val functionName = MultiLanguageFunctionDetector.getFunctionSignature(rootFunction)
        sb.appendLine("## Function")
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
        
        sb.appendLine("*Note: maxDepth=0 extracts only the function at cursor, no call traversal.*")
        
        return sb.toString()
    }
}
