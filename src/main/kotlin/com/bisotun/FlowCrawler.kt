package com.bisotun

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.concurrency.annotations.RequiresReadLock

class FlowCrawler(private val project: Project) {

    private val log = Logger.getInstance(FlowCrawler::class.java)

    private fun isInContent(file: PsiFile?): Boolean {
        if (file == null) return false
        val vf = file.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInContent(vf)
    }

    private fun isLibraryElement(element: PsiElement): Boolean {
        val file = element.containingFile ?: return true
        return !isInContent(file)
    }

    @RequiresReadLock
    fun crawl(root: PsiMethod, maxDepth: Int = 4, useFallback: Boolean = true): List<PsiElement> {
        val visited = linkedSetOf<String>()
        val result = mutableListOf<PsiElement>()
        crawlMethod(root, 0, maxDepth, visited, result, useFallback)
        return result
    }

    @RequiresReadLock
    private fun crawlMethod(
        method: PsiMethod?,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
        result: MutableList<PsiElement>,
        useFallback: Boolean
    ) {
        // BASE CASE: If depth >= maxDepth, stop recursion
        // When maxDepth = 0: only the root method is included (no children crawled)
        // When maxDepth = 1: root's direct children are crawled but not further
        if (method == null || depth >= maxDepth) return

        try {
            val body = method.body ?: return

            body.accept(object : JavaRecursiveElementWalkingVisitor() {

                override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                    super.visitMethodCallExpression(expression)
                    try {
                        val resolved = expression.resolveMethod() ?: return
                        handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
                    } catch (e: Exception) {
                        log.debug("Failed to resolve method call: ${e.message}")
                    }
                }

                override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                    super.visitMethodReferenceExpression(expression)
                    try {
                        val resolved = expression.resolve() as? PsiMethod ?: return
                        handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
                    } catch (e: Exception) {
                        log.debug("Failed to resolve method reference: ${e.message}")
                    }
                }

                override fun visitNewExpression(expression: PsiNewExpression) {
                    super.visitNewExpression(expression)
                    try {
                        val resolved = expression.resolveConstructor() ?: return
                        handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
                    } catch (e: Exception) {
                        log.debug("Failed to resolve constructor: ${e.message}")
                    }
                }
            })
        } catch (e: Exception) {
            log.warn("Error crawling method ${method.name}: ${e.message}")
        }
    }

    @RequiresReadLock
    private fun handleResolvedMethod(
        resolved: PsiMethod,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
        result: MutableList<PsiElement>,
        useFallback: Boolean
    ) {
        if (isLibraryElement(resolved)) return

        val vf = resolved.containingFile?.virtualFile ?: return
        val key = "${vf.path}:${resolved.textRange.startOffset}-${resolved.textRange.endOffset}"
        if (!visited.add(key)) return

        result.add(resolved)

        try {
            // If it's an interface, look for implementations
            if (resolved.containingClass?.isInterface == true) {
                val impls = DefinitionsScopedSearch.search(resolved).findAll()
                for (impl in impls) {
                    val implMethod = impl as? PsiMethod ?: continue
                    if (isLibraryElement(implMethod)) continue

                    val implVf = implMethod.containingFile?.virtualFile ?: continue
                    val implKey = "${implVf.path}:${implMethod.textRange.startOffset}-${implMethod.textRange.endOffset}"
                    if (!visited.add(implKey)) continue

                    result.add(implMethod)
                    // Recurse only if we haven't reached maxDepth yet
                    crawlMethod(implMethod, depth + 1, maxDepth, visited, result, useFallback)
                }
            } else {
                // Recurse only if we haven't reached maxDepth yet
                crawlMethod(resolved, depth + 1, maxDepth, visited, result, useFallback)
            }
        } catch (e: Exception) {
            log.debug("Error handling resolved method ${resolved.name}: ${e.message}")
        }
    }
}
