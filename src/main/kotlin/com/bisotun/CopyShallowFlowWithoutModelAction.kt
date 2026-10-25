package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction

class CopyShallowFlowWithoutModelAction : ExtractFlowActionBase(
    maxDepth = 1,
    includeModels = false,
    actionText = "Copy Shallow Flow (No Models)",
    actionDescription = "Extract shallow execution call flow without data models to clipboard",
    shortcut = "ctrl alt shift M"
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

        val rootMethod = ReadAction.compute<PsiMethod, Exception> {
            psiFile.findElementAt(editor.caretModel.offset)
                ?.let { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java) }
        }

        if (rootMethod == null) {
            showWarningNotification(project, "No method found at cursor position.")
            return
        }

        val formatter = MarkdownFormatter(project)
        val markdown = formatter.format(rootMethod, nodes, models, includeModels = false)

        copyToClip(markdown)
        showMessage(project, "Shallow flow with ${nodes.size} methods (no models) copied to clipboard", "Copy Shallow Flow (No Models)")
    }
}
