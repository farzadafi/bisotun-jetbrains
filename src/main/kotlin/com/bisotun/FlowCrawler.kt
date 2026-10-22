package com.bisotun

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.searches.DefinitionsScopedSearch

class FlowCrawler(private val project: Project) {

    private fun isInContent(file: PsiFile?): Boolean {
        if (file == null) return false
        val vf = file.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInContent(vf)
    }

    private fun isLibraryElement(element: PsiElement): Boolean {
        val file = element.containingFile ?: return true
        return !isInContent(file)
    }

    fun crawl(root: PsiMethod, maxDepth: Int = 4, useFallback: Boolean = true): List<PsiElement> {
        val visited = linkedSetOf<String>()
        val result = mutableListOf<PsiElement>()
        crawlMethod(root, 0, maxDepth, visited, result, useFallback)
        return result
    }

    private fun crawlMethod(
        method: PsiMethod?,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
        result: MutableList<PsiElement>,
        useFallback: Boolean
    ) {
        if (method == null || depth >= maxDepth) return
        val body = method.body ?: return

        body.accept(object : JavaRecursiveElementWalkingVisitor() {

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                super.visitMethodCallExpression(expression)
                val resolved = expression.resolveMethod() ?: return
                handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
            }

            override fun visitMethodReferenceExpression(expression: PsiMethodReferenceExpression) {
                super.visitMethodReferenceExpression(expression)
                val resolved = expression.resolve() as? PsiMethod ?: return
                handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
            }

            override fun visitNewExpression(expression: PsiNewExpression) {
                super.visitNewExpression(expression)
                val resolved = expression.resolveConstructor() ?: return
                handleResolvedMethod(resolved, depth, maxDepth, visited, result, useFallback)
            }
        })
    }

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
                crawlMethod(implMethod, depth + 1, maxDepth, visited, result, useFallback)
            }
        } else {
            crawlMethod(resolved, depth + 1, maxDepth, visited, result, useFallback)
        }
    }
}
