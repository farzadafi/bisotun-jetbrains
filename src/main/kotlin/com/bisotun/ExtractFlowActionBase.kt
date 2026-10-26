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
     * Supports Java and JavaScript/TypeScript
     */
    protected fun crawlFromRoot(
        project: Project,
        psiFile: PsiFile,
        editor: Editor,
        maxDepth: Int,
        useFallback: Boolean
    ): Pair<List<PsiElement>, List<PsiElement>> {
        val rootFunction = ReadAction.compute<PsiElement?, Throwable> {
            MultiLanguageFunctionDetector.findFunctionAtCaret(psiFile, editor.caretModel.offset)
        }

        if (rootFunction == null) {
            return Pair(emptyList(), emptyList())
        }

        return ReadAction.compute<Pair<List<PsiElement>, List<PsiElement>>, Throwable> {
            try {
                if (rootFunction is PsiMethod) {
                    // Java/Kotlin path
                    val crawler = FlowCrawler(project)
                    val nodes = crawler.crawl(rootFunction, maxDepth = maxDepth, useFallback = useFallback)

                    val modelExtractor = ModelExtractor(project)
                    val allMethods = listOf(rootFunction) + nodes.filterIsInstance<PsiMethod>()
                    val models = if (includeModels) {
                        allMethods.flatMap { modelExtractor.extractModels(it) }.distinct()
                    } else emptyList()

                    Pair(nodes, models)
                } else {
                    // JavaScript/TypeScript path - simple implementation for now
                    val nodes = mutableListOf<PsiElement>()
                    val models = mutableListOf<PsiElement>()
                    
                    // For JS/TS, we can extract inline call expressions from the function body
                    extractJsCallExpressions(rootFunction, nodes, maxDepth)
                    
                    // Extract models for JS/TS if includeModels is true
                    if (includeModels) {
                        extractJsTypeReferences(rootFunction, models)
                    }
                    
                    Pair(nodes, models)
                }
            } catch (e: Exception) {
                log.error("Error during flow crawling", e)
                Pair(emptyList(), emptyList())
            }
        }
    }

    /**
     * Extract call expressions from JavaScript/TypeScript function
     */
    private fun extractJsCallExpressions(
        functionElement: PsiElement,
        result: MutableList<PsiElement>,
        maxDepth: Int,
        currentDepth: Int = 0
    ) {
        if (currentDepth >= maxDepth) return
        
        // Simple extraction - look for function calls in the text
        val text = functionElement.text ?: ""
        
        // Find function calls (simple pattern matching)
        val callPattern = Regex("""\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^)]*\)""")
        val matches = callPattern.findAll(text)
        
        for (match in matches) {
            val callName = match.groupValues[1]
            // Skip common built-ins
            if (callName !in setOf("console", "alert", "log", "error", "warn", "info", "debug")) {
                // Create a simple representation of the call
                result.add(functionElement)
            }
        }
        
        // For now, we don't do deep recursion for JS/TS
    }
    
    /**
     * Extract type references from JavaScript/TypeScript function
     */
    private fun extractJsTypeReferences(
        functionElement: PsiElement,
        result: MutableList<PsiElement>
    ) {
        val text = functionElement.text ?: ""
        
        // Look for TypeScript type references
        val typePattern = Regex("""\b(?:interface|type|class|enum)\s+([A-Za-z_$][A-Za-z0-9_$]*)\b""")
        val matches = typePattern.findAll(text)
        
        for (match in matches) {
            val typeName = match.groupValues[1]
            // Add the function element itself as a placeholder
            // In a real implementation, we would resolve the actual type definition
            result.add(functionElement)
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
     * Now supports multi-language
     */
    protected fun isCaretOnMethod(psiFile: PsiFile?, editor: Editor?): Boolean {
        if (psiFile == null || editor == null) return false
        
        // Quick check: if it's a Java file, check for PsiMethod
        if (MultiLanguageFunctionDetector.isJavaOrKotlinFile(psiFile)) {
            val offset = editor.caretModel.offset
            val element = psiFile.findElementAt(offset) ?: return false
            return PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) != null
        }
        
        // For other files, we'll do a more thorough check
        return ReadAction.compute<Boolean, Exception> {
            MultiLanguageFunctionDetector.findFunctionAtCaret(psiFile, editor.caretModel.offset) != null
        } ?: false
    }
    
    /**
     * Get root function element for any language
     */
    protected fun getRootFunction(psiFile: PsiFile, editor: Editor): PsiElement? {
        return ReadAction.compute<PsiElement?, Exception> {
            MultiLanguageFunctionDetector.findFunctionAtCaret(psiFile, editor.caretModel.offset)
        }
    }
}
