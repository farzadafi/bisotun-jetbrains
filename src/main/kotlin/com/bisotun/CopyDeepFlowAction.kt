package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction

class CopyDeepFlowAction : ExtractFlowActionBase(
    maxDepth = 4,
    includeModels = true,
    actionText = "Copy Deep Flow",
    actionDescription = "Extract deep execution call flow and data models to clipboard",
    shortcut = "ctrl alt shift D"
) {
    override fun performAction(project: Project, psiFile: PsiFile, editor: Editor) {
        // Check if caret is on a method
        val hasMethod = ReadAction.compute<Boolean, Exception> {
            isCaretOnMethod(psiFile, editor)
        }
        
        if (!hasMethod) {
            showWarningNotification(project, "Please place the caret inside a method or function to extract the flow.")
            return
        }

        val (nodes, models) = crawlFromRoot(project, psiFile, editor, maxDepth = this.maxDepth, useFallback = true)

        if (nodes.isEmpty()) {
            showWarningNotification(project, "No method found at cursor position. Please place the caret inside a method.")
            return
        }

        val methodName = ReadAction.compute<String, Exception> {
            psiFile.findElementAt(editor.caretModel.offset)
                ?.let { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java) }
                ?.name ?: "Unknown Method"
        }

        val markdown = buildString {
            appendLine("## Deep Flow Extracted by Bisotun")
            appendLine("**Configuration:** maxDepth=4, includeModels=true")
            appendLine("- Extracted Nodes: ${nodes.size}")
            appendLine("- Extracted Models: ${models.size}")
            appendLine()
            appendLine("### Root Method")
            appendLine("- Method: `$methodName`")
            appendLine()
            if (nodes.isNotEmpty()) {
                appendLine("### Execution Flow")
                nodes.take(10).forEachIndexed { index, node ->
                    if (node is PsiMethod) {
                        appendLine("${index + 1}. `${node.containingClass?.name}.${node.name}()`")
                    }
                }
                if (nodes.size > 10) {
                    appendLine("... and ${nodes.size - 10} more methods")
                }
            }
        }

        copyToClip(markdown)
        showMessage(project, "Deep flow extracted: ${nodes.size} nodes, ${models.size} models", "Copy Deep Flow")
    }
}
