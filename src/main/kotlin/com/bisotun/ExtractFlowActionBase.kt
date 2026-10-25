package com.bisotun

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService

abstract class ExtractFlowActionBase(
    val maxDepth: Int = 4,
    val includeModels: Boolean = true,
    val actionText: String = "Extract Flow",
    val actionDescription: String = "Extract execution call flow and data models",
    val shortcut: String = "ctrl alt shift F"
) : AnAction() {

    private val log = Logger.getInstance(this::class.java)

    abstract fun performAction(project: Project, psiFile: PsiFile, editor: Editor)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        // Check if in dumb mode (indexing)
        if (DumbService.getInstance(project).isDumb) {
            showWarningNotification(project, "Cannot extract flow while indexing is in progress. Please wait.")
            return
        }

        try {
            performAction(project, psiFile, editor)
        } catch (ex: Exception) {
            log.error("Bisotun action failed", ex)
            ApplicationManager.getApplication().invokeLater {
                Messages.showErrorDialog(project, ex.message ?: "Unknown error", "Bisotun Error")
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        
        // Disable during indexing
        val isDumb = project?.let { DumbService.getInstance(it).isDumb } ?: true
        
        e.presentation.isEnabledAndVisible = psiFile != null && editor != null && !isDumb
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    /**
     * Shared: crawl from root method with proper read action handling
     * Simple synchronous version for now to fix compilation
     */
    protected fun crawlFromRoot(
        project: Project,
        psiFile: PsiFile,
        editor: Editor,
        maxDepth: Int,
        useFallback: Boolean
    ): Pair<List<PsiElement>, List<PsiElement>> {
        var rootMethod: PsiMethod? = null

        // Read action for PSI operations
        ReadAction.run<Throwable> {
            val element = psiFile.findElementAt(editor.caretModel.offset)
            rootMethod = if (element != null) {
                PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            } else {
                null
            }
        }

        if (rootMethod == null) {
            return Pair(emptyList(), emptyList())
        }

        val finalRootMethod = rootMethod!!

        return ReadAction.compute<Pair<List<PsiElement>, List<PsiElement>>, Throwable> {
            try {
                val crawler = FlowCrawler(project)
                val nodes = crawler.crawl(finalRootMethod, maxDepth = maxDepth, useFallback = useFallback)

                val modelExtractor = ModelExtractor(project)
                val allMethods = listOf(finalRootMethod) + nodes.filterIsInstance<PsiMethod>()
                val models = if (includeModels) {
                    allMethods.flatMap { modelExtractor.extractModels(it) }.distinct()
                } else emptyList()

                Pair(nodes, models)
            } catch (e: Exception) {
                log.error("Error during flow crawling", e)
                Pair(emptyList(), emptyList())
            }
        }
    }

    // Shared: clipboard - must be called on EDT
    protected fun copyToClip(markdown: String) {
        ApplicationManager.getApplication().invokeLater {
            CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        }
    }

    // Shared: message - must be called on EDT
    protected fun showMessage(project: Project, message: String, title: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, title)
        }
    }

    // Warning notification
    protected fun showWarningNotification(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showWarningDialog(project, message, "Bisotun Flow Extractor")
        }
    }

    /**
     * Check if caret is on a valid method/function (to be called from update)
     * Safe for EDT - does minimal work
     */
    protected fun isCaretOnMethod(psiFile: PsiFile?, editor: Editor?): Boolean {
        if (psiFile == null || editor == null) return false
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return false
        return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) != null
    }
}
