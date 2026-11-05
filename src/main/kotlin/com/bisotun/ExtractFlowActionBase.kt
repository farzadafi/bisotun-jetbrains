package com.bisotun

import com.bisotun.domain.FlowResult
import com.bisotun.domain.ResolutionStatus
import com.bisotun.language.FlowExtractionDispatcher
import com.bisotun.language.LanguageKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.awt.datatransfer.StringSelection

abstract class ExtractFlowActionBase(
    private val maxDepth: Int = 4,
    private val includeModels: Boolean = true,
    private val actionText: String = "Extract Flow"
) : AnAction() {

    private val log = Logger.getInstance(ExtractFlowActionBase::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        if (DumbService.getInstance(project).isDumb) {
            showWarningNotification(project, "Cannot extract flow while indexing is in progress. Please wait.")
            return
        }

        try {
            val outcome = runReadAction {
                FlowExtractionDispatcher(project).extract(psiFile, editor, maxDepth, includeModels)
            }

            val result = outcome.result
            if (result == null) {
               showWarningNotification(project, outcome.message ?: DEFAULT_MESSAGE)
                return
            }

            val markdown = MarkdownFormatter().format(result, maxDepth, includeModels)
            copyToClip(markdown)
            showMessage(project, buildSummary(result, outcome.languageKind), actionText)
        } catch (ex: Throwable) {
            log.error("Bisotun action '$actionText' failed", ex)
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, ex.message ?: "Unknown error", "Bisotun Error")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val enabled = project != null &&
            !DumbService.getInstance(project).isDumb &&
            e.getData(CommonDataKeys.PSI_FILE) != null &&
            e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.isEnabled = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun buildSummary(result: FlowResult, languageKind: LanguageKind): String {
        val calls = result.nodes.count { it.resolutionStatus == ResolutionStatus.RESOLVED }
        val unresolved = result.nodes.size - calls
        val models = result.models.size
        val label = if (languageKind == LanguageKind.UNKNOWN) "Flow" else "${languageKind.displayName} flow"
        val details = if (unresolved == 0) "$calls call(s)" else "$calls call(s), $unresolved unresolved"
        return "$label with $details and $models model(s) copied to clipboard"
    }

    protected fun copyToClip(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    protected fun showMessage(project: Project, message: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, title)
        }
    }

    protected fun showWarningNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, message, "Bisotun")
        }
    }

    private companion object {
        const val DEFAULT_MESSAGE = "Please place the caret inside a method or function."
    }
}
