package com.bisotun

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction

class CopyMethodOnlyAction : ExtractFlowActionBase(
    maxDepth = 0,
    includeModels = false,
    actionText = "Copy Method Only",
    actionDescription = "Extract only the current method/code at cursor position",
    shortcut = "ctrl alt shift C"
) {
    override fun performAction(project: Project, psiFile: PsiFile, editor: Editor) {
        // Check if caret is on a method
        val hasMethod = ReadAction.compute<Boolean, Exception> {
            isCaretOnMethod(psiFile, editor)
        }
        
        if (!hasMethod) {
            showWarningNotification(project, "Please place the caret inside a method or function to extract it.")
            return
        }

        // For maxDepth=0, we only get the root method without crawling
        val methodPair = ReadAction.compute<Pair<String, String>?, Exception> {
            psiFile.findElementAt(editor.caretModel.offset)
                ?.let { PsiTreeUtil.getParentOfType(it, PsiMethod::class.java) }
                ?.let { method ->
                    Pair(method.name ?: "Unknown", method.text ?: "")
                }
        }

        if (methodPair == null) {
            showWarningNotification(project, "No method found at cursor position.")
            return
        }

        val (name, code) = methodPair

        val markdown = buildString {
            appendLine("## Method Extracted by Bisotun")
            appendLine("**Configuration:** maxDepth=0, includeModels=false")
            appendLine("- Method: `$name`")
            appendLine("- Recursion: None (only the method at cursor)")
            appendLine()
            appendLine("### Method Code")
            appendLine("```java")
            appendLine(code.toString())
            appendLine("```")
        }

        copyToClip(markdown)
        showMessage(project, "Method `$name` copied to clipboard", "Copy Method Only")
    }
}
