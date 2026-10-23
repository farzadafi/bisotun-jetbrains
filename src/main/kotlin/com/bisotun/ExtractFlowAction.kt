package com.bisotun

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlin.math.roundToLong

class ExtractFlowAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset)

        // 1) Java/Kotlin PSI path
        val rootMethod = if (element != null) PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) else null
        if (rootMethod != null) {
            val crawler = FlowCrawler(project)
            val nodes = crawler.crawl(rootMethod, maxDepth = 4, useFallback = true)

            val modelExtractor = ModelExtractor(project)
            val allMethods = listOf(rootMethod) + nodes.filterIsInstance<PsiMethod>()
            val models = allMethods.flatMap { modelExtractor.extractModels(it) }.distinct()

            val formatter = MarkdownFormatter(project)
            val markdown = formatter.format(rootMethod, nodes, models)

            CopyPasteManager.getInstance().setContents(StringSelection(markdown))
            Messages.showInfoMessage(project, "Flow & models extracted to clipboard!", "Extract Flow")
            return
        }

        // 2) JS/TS text-based path
        val strategy = resolveStrategy(psiFile)
        if (strategy == null) {
            Messages.showWarningDialog(
                project,
                "Please place caret inside a method or function.",
                "Extract Flow"
            )
            return
        }

        val scope = strategy.findEnclosingScope(psiFile, offset)
        if (scope == null) {
            Messages.showWarningDialog(
                project,
                "Please place caret inside a function or component body.",
                "Extract Flow"
            )
            return
        }

        val fileText = psiFile.text ?: ""
        val rootSnippet = extractLines(fileText, scope.startLine, scope.endLine)

        val initialFile = psiFile.virtualFile?.let { File(it.path) }

        val (crawledDependencies, collectedCode) = crawlJsByImports(
            rootFile = initialFile,
            rootCode = rootSnippet
        )

        // collect involved files (root + dependency files)
        val involvedFiles: List<File> =
            (listOfNotNull(initialFile) + crawledDependencies.map { File(it.filePath) })
                .distinctBy { it.canonicalPath }

        val dummyPsi = psiFile.findElementAt(offset) ?: psiFile

        val typeCandidates = strategy.extractTypeCandidates(collectedCode)

        val models = typeCandidates.mapNotNull { typeName ->
            resolveModelAcrossFiles(project, involvedFiles, typeName, dummyPsi, strategy)
        }.distinctBy { it.name }

        val q = '`'
        val ext = psiFile.virtualFile?.extension?.lowercase() ?: "ts"
        val lang = if (ext in listOf("js", "jsx")) "javascript" else "typescript"

        val markdown = buildString {
            appendLine("# Context Flow Extracted by Bisotun")
            appendLine("- Extracted Nodes: ${crawledDependencies.size + 1}")
            appendLine("- Extracted Models: ${models.size}")

            val totalChars =
                rootSnippet.length +
                    crawledDependencies.sumOf { it.code.length } +
                    models.sumOf { it.code.length }
            appendLine("- Estimated Tokens: ~${(totalChars / 4.0).roundToLong()}")
            appendLine()

            appendLine("## Root Method")
            appendLine("### $q${scope.className}#${scope.methodName}$q")
            appendLine("- File: $q${getFileRelativePath(psiFile)}$q")
            appendLine("- Lines: ${scope.startLine}-${scope.endLine}")
            appendLine()
            appendLine("$q$q$q$lang")
            appendLine(rootSnippet)
            appendLine("$q$q$q")
            appendLine()

            if (crawledDependencies.isNotEmpty()) {
                appendLine("## Execution Dependencies")
                for (dep in crawledDependencies) {
                    appendLine("### $q${dep.signature}$q")
                    val relPath = getRelativePath(project.basePath, dep.filePath)
                    appendLine("- File: $q$relPath$q")
                    if (dep.startLine > 0 && dep.endLine > 0) {
                        appendLine("- Lines: ${dep.startLine}-${dep.endLine}")
                    }
                    appendLine()
                    appendLine("$q$q$q$lang")
                    appendLine(dep.code)
                    appendLine("$q$q$q")
                    appendLine()
                }
            }

            if (models.isNotEmpty()) {
                appendLine("## Related Data Models (Types / Interfaces / Schemas)")
                for (model in models) {
                    appendLine("### $q${model.name}$q")
                    val relativeModelPath = getRelativePath(project.basePath, model.filePath)
                    appendLine("- File: $q$relativeModelPath$q")
                    appendLine()
                    appendLine("$q$q$q$lang")
                    appendLine(model.code)
                    appendLine("$q$q$q")
                    appendLine()
                }
            }
        }

        CopyPasteManager.getInstance().setContents(StringSelection(markdown))
        Messages.showInfoMessage(project, "Flow & models extracted to clipboard!", "Extract Flow")
    }

    private data class ExtractedJsNode(
        val signature: String,
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val code: String
    )

    private fun resolveModelAcrossFiles(
        project: com.intellij.openapi.project.Project,
        involvedFiles: List<File>,
        typeName: String,
        dummyPsi: com.intellij.psi.PsiElement,
        strategy: LanguageStrategy
    ): ModelDefinition? {
        for (f in involvedFiles) {
            val vf = LocalFileSystem.getInstance().findFileByIoFile(f) ?: continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            val md = strategy.resolveModelDefinition(psi, typeName, dummyPsi)
            if (md != null) return md
        }
        return null
    }

    private fun crawlJsByImports(
        rootFile: File?,
        rootCode: String
    ): Pair<List<ExtractedJsNode>, String> {
        val results = mutableListOf<ExtractedJsNode>()
        val codeBuilder = StringBuilder(rootCode)
        val visited = mutableSetOf<String>()

        var currentFile = rootFile
        var currentSnippet = rootCode
        var depth = 0

        while (currentFile != null && depth < 5) {
            depth++
            val calls = extractCalls(currentSnippet)
            if (calls.isEmpty()) break

            var nextFile: File? = null
            var nextSnippet: String? = null

            for (call in calls) {
                val resolved = resolveCallTarget(currentFile, call)
                if (resolved != null) {
                    val key = "${resolved.filePath}:${resolved.signature}"
                    if (visited.add(key)) {
                        results.add(resolved)
                        codeBuilder.append("\n").append(resolved.code)
                        nextFile = File(resolved.filePath)
                        nextSnippet = resolved.code
                        break
                    }
                }
            }

            currentFile = nextFile
            currentSnippet = nextSnippet ?: ""
        }

        return Pair(results, codeBuilder.toString())
    }

    private fun extractCalls(code: String): List<String> {
        val calls = mutableListOf<String>()

        // handle patterns like:
        // this.invoiceService.createInvoice(...)
        // invoiceRepo.saveInvoice(...)
        val regex = Regex("""\b(?:this\.)?[A-Za-z0-9_$]+\.(\w+)\s*\(""")
        regex.findAll(code).forEach { match ->
            val method = match.groupValues[1]
            if (method !in setOf("log", "error", "warn", "info", "map", "filter", "forEach", "then", "catch")) {
                calls.add(method)
            }
        }

        return calls
    }

    private fun resolveCallTarget(sourceFile: File, methodName: String): ExtractedJsNode? {
        if (!sourceFile.exists()) return null

        val sourceContent = sourceFile.readText()
        val dir = sourceFile.parentFile ?: return null

        // gather import paths
        val importRegex = Regex("""import\s+.*?from\s+['"]([^'"]+)['"]""")
        for (match in importRegex.findAll(sourceContent)) {
            val relPath = match.groupValues[1]
            val candidateFile = resolvePath(dir, relPath) ?: continue

            val content = candidateFile.readText()

            // Supports TS modifiers + return type
            val methodRegex = Regex(
                """(?m)^\s*(?:(?:public|private|protected|static|readonly|async|override)\s+)*$methodName\s*\([^)]*\)\s*(?::\s*[^{]+)?\{"""
            )
            val matchMethod = methodRegex.find(content) ?: continue

            val lines = content.lines()
            val offset = matchMethod.range.first
            val startLine = content.substring(0, offset).count { it == '\n' }

            var braceCount = 0
            var foundStart = false
            var endLine = lines.size - 1

            for (i in startLine until lines.size) {
                val l = lines[i]
                for (ch in l) {
                    if (ch == '{') {
                        braceCount++
                        foundStart = true
                    } else if (ch == '}') {
                        braceCount--
                    }
                }
                if (foundStart && braceCount <= 0) {
                    endLine = i
                    break
                }
            }

            val snippet = lines.subList(startLine, endLine + 1).joinToString("\n")
            val className =
                Regex("""class\s+([A-Za-z0-9_$]+)""").find(content)?.groupValues?.get(1)
                    ?: candidateFile.nameWithoutExtension

            return ExtractedJsNode(
                signature = "$className#$methodName",
                filePath = candidateFile.canonicalPath,
                startLine = startLine + 1,
                endLine = endLine + 1,
                code = snippet
            )
        }

        return null
    }

    private fun resolvePath(baseDir: File, relPath: String): File? {
        val extensions = listOf("", ".ts", ".js", ".tsx", ".jsx")
        for (ext in extensions) {
            val f = File(baseDir, "$relPath$ext")
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = psiFile != null && editor != null
    }

    private fun resolveStrategy(psiFile: PsiFile): LanguageStrategy? {
        val ext = psiFile.virtualFile?.extension?.lowercase() ?: ""
        val langId = psiFile.language.id.lowercase()
        val jsStrategy = JavaScriptStrategy()

        if (jsStrategy.supports(ext) || jsStrategy.supports(langId)) {
            return jsStrategy
        }
        return null
    }

    private fun extractLines(text: String, startLine1Based: Int, endLine1Based: Int): String {
        if (text.isEmpty()) return ""
        val lines = text.lines()
        val start = (startLine1Based - 1).coerceIn(0, lines.size)
        val endExclusive = endLine1Based.coerceIn(0, lines.size)
        if (start >= endExclusive) return ""
        return lines.subList(start, endExclusive).joinToString("\n")
    }

    private fun getFileRelativePath(file: PsiFile): String {
        val projectBase = file.project.basePath ?: return file.name
        val path = file.virtualFile?.path ?: return file.name
        return getRelativePath(projectBase, path)
    }

    private fun getRelativePath(basePath: String?, fullPath: String): String {
        if (basePath != null && fullPath.startsWith(basePath)) {
            return fullPath.removePrefix(basePath).removePrefix("/")
        }
        return fullPath
    }
}
