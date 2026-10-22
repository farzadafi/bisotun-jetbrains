package com.bisotun

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.io.File
import kotlin.math.max

private val JAVASCRIPT_BUILT_IN_TYPES = setOf(
    "Array", "ArrayBuffer", "BigInt", "Boolean", "Buffer", "Date", "Error",
    "File", "FormData", "Function", "HTMLElement", "JSON", "Math", "NaN",
    "NodeJS", "Number", "Object", "Promise", "Readonly", "ReadonlyArray",
    "Record", "RegExp", "Request", "Response", "Set", "String", "Symbol",
    "URL", "URLSearchParams", "WeakMap", "WeakSet", "AbortController",
    "Blob", "Console", "Event", "EventTarget", "Infinity", "Partial", "Pick",
    "Omit", "Required", "ReturnType", "Parameters", "InstanceType", "Awaited",
    "React", "ReactNode", "FC", "PropsWithChildren", "SyntheticEvent",
    // TS primitives
    "void", "any", "unknown", "never", "string", "number", "boolean", "undefined", "null"
)

private val JS_KEYWORD_NAMES = setOf(
    "if", "for", "while", "switch", "catch", "return", "function", "require",
    "import", "constructor", "super", "this", "new", "export", "class", "default",
    "public", "private", "protected", "static", "readonly", "override"
)

class JavaScriptStrategy : LanguageStrategy {
    override val languageId: String = "javascript"

    override fun supports(languageId: String): Boolean {
        val lower = languageId.lowercase()
        return listOf(
            "javascript", "typescript", "javascriptreact", "typescriptreact",
            "js", "ts", "jsx", "tsx", "ecmascript 6"
        ).any { lower.contains(it) }
    }

    override fun extractTypeCandidates(code: String): List<String> {
        val candidates = mutableSetOf<String>()
        val regex = Regex("""\b[A-Z][A-Za-z0-9_$]*\b""")
        regex.findAll(code).forEach { match ->
            val candidate = match.value
            if (!JAVASCRIPT_BUILT_IN_TYPES.contains(candidate)) {
                candidates.add(candidate)
            }
        }
        return candidates.toList()
    }

    override fun findEnclosingScope(file: PsiFile, position: Int): EnclosingScope? {
        val doc: Document =
            PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return null

        val safePos = position.coerceIn(0, doc.textLength)
        val targetLine = doc.getLineNumber(safePos)

        val text = file.text
        val lines = text.lines()

        // Supports TS modifiers + return type:  public m(a: X): Y { ... }
        val methodPattern = Regex(
            """(?:(?:public|private|protected|static|readonly|async|override)\s+)*([A-Za-z0-9_$]+)\s*\([^)]*\)\s*(?::\s*[^{]+)?\{"""
        )
        val classPattern = Regex("""(?:export\s+)?(?:default\s+)?(?:class|interface)\s+([A-Za-z0-9_$]+)""")

        var foundMethod: String? = null
        var startLine = -1
        var currentClass = file.virtualFile?.nameWithoutExtension ?: "Module"

        for (i in 0..targetLine) {
            val line = lines.getOrNull(i) ?: continue

            classPattern.find(line)?.let { currentClass = it.groupValues[1] }

            val match = methodPattern.find(line)
            if (match != null) {
                val name = match.groupValues[1]
                if (!JS_KEYWORD_NAMES.contains(name)) {
                    foundMethod = name
                    startLine = i
                }
            }
        }

        if (foundMethod == null || startLine == -1) return null

        var braceCount = 0
        var foundStartBrace = false
        var endLine = lines.size - 1

        for (i in startLine until lines.size) {
            val line = lines[i]
            for (ch in line) {
                if (ch == '{') {
                    braceCount++
                    foundStartBrace = true
                } else if (ch == '}') {
                    braceCount--
                }
            }
            if (foundStartBrace && braceCount <= 0) {
                endLine = i
                break
            }
        }

        if (targetLine > endLine) return null

        return EnclosingScope(
            className = currentClass,
            methodName = foundMethod,
            startLine = startLine + 1,
            endLine = max(startLine + 1, endLine + 1)
        )
    }

    override fun resolveModelDefinition(
        file: PsiFile,
        typeName: String,
        methodRange: PsiElement
    ): ModelDefinition? {
        // 1) current file
        extractModelSnippet(file.text, typeName)?.let { snippet ->
            return ModelDefinition(
                name = typeName,
                filePath = file.virtualFile?.path ?: file.name,
                code = snippet
            )
        }

        // 2) import-based lookup (same directory)
        val currentDir = file.virtualFile?.parent?.path ?: return null

        val importRegex = Regex(
            """import\s+.*?\{?[^}]*\b${Regex.escape(typeName)}\b[^}]*\}?.*?\s+from\s+['"]([^'"]+)['"]"""
        )
        val match = importRegex.find(file.text)
        val relPath = match?.groupValues?.get(1) ?: "./$typeName"

        val extensions = listOf("", ".ts", ".js", ".tsx", ".jsx")
        for (ext in extensions) {
            val targetFile = File("$currentDir/$relPath$ext")
            if (targetFile.exists() && targetFile.isFile) {
                val content = targetFile.readText()
                extractModelSnippet(content, typeName)?.let { snippet ->
                    return ModelDefinition(
                        name = typeName,
                        filePath = targetFile.canonicalPath,
                        code = snippet
                    )
                }
            }
        }

        return null
    }

    // O(N) extraction, no catastrophic regex backtracking
    private fun extractModelSnippet(content: String, typeName: String): String? {
        val declPattern = Regex(
            """(?m)^\s*(?:export\s+(?:default\s+)?)?(?:class|interface|type|enum)\s+${Regex.escape(typeName)}\b"""
        )
        val match = declPattern.find(content) ?: return null
        val startOffset = match.range.first

        val lines = content.lines()
        val startLine = content.substring(0, startOffset).count { it == '\n' }

        var braceCount = 0
        var foundStartBrace = false
        var endLine = lines.size - 1

        for (i in startLine until lines.size) {
            val line = lines[i]
            for (ch in line) {
                if (ch == '{') {
                    braceCount++
                    foundStartBrace = true
                } else if (ch == '}') {
                    braceCount--
                }
            }

            if (foundStartBrace && braceCount <= 0) {
                endLine = i
                break
            }

            // type X = ...; (single-line or until ';' appears without '{')
            if (!foundStartBrace && line.contains(";")) {
                endLine = i
                break
            }
        }

        return lines.subList(startLine, endLine + 1).joinToString("\n").trim()
    }

    override fun resolveImplementationLocation(file: PsiFile, position: Int): ImplementationLocation? = null
    override fun resolveFallbackCalls(file: PsiFile, methodRange: PsiElement): List<FallbackCall> = emptyList()
}
