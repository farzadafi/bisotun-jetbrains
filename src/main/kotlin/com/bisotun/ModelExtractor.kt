package com.bisotun

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil
import com.intellij.util.concurrency.annotations.RequiresReadLock

class ModelExtractor(private val project: Project) {

    private val log = Logger.getInstance(ModelExtractor::class.java)

    @RequiresReadLock
    private fun isInContent(element: PsiElement): Boolean {
        val vf = element.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInContent(vf)
    }

    @RequiresReadLock
    fun extractModels(method: PsiMethod): List<PsiElement> {
        val visited = linkedSetOf<String>()
        val result = mutableListOf<PsiElement>()

        try {
            method.returnType?.let { inspectTypeDeep(it, visited, result) }
            for (p in method.parameterList.parameters) {
                inspectTypeDeep(p.type, visited, result)
            }
        } catch (e: Exception) {
            log.warn("Error extracting models from method ${method.name}: ${e.message}")
        }

        return result
    }

    @RequiresReadLock
    private fun inspectTypeDeep(type: PsiType, visited: MutableSet<String>, result: MutableList<PsiElement>) {
        try {
            when (type) {
                is PsiPrimitiveType -> return

                is PsiArrayType -> {
                    inspectTypeDeep(type.componentType, visited, result)
                }

                is PsiWildcardType -> {
                    type.bound?.let { inspectTypeDeep(it, visited, result) }
                }

                is PsiEllipsisType -> {
                    inspectTypeDeep(type.componentType, visited, result)
                }

                is PsiClassType -> {
                    val cls = type.resolve()
                    if (cls != null) {
                        extractClassModel(cls, visited, result)
                    }

                    // Extract generic parameters: e.g., List<ChapterResponse> -> ChapterResponse
                    for (param in type.parameters) {
                        inspectTypeDeep(param, visited, result)
                    }
                }

                else -> {
                    val cls = PsiUtil.resolveClassInType(type)
                    if (cls != null) {
                        extractClassModel(cls, visited, result)
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Error inspecting type ${type.presentableText}: ${e.message}")
        }
    }

    @RequiresReadLock
    private fun extractClassModel(cls: PsiClass, visited: MutableSet<String>, result: MutableList<PsiElement>) {
        if (!isInContent(cls)) return

        val fqn = cls.qualifiedName ?: return
        if (!visited.add(fqn)) return

        val lowerName = cls.name?.lowercase() ?: ""

        // Filter out service/controller/repository layers
        if (lowerName.endsWith("service") || lowerName.endsWith("controller") || lowerName.endsWith("repository")) {
            return
        }

        // Include entities, records, interfaces (like Spring Projection), and data classes
        if (cls.isEnum || cls.isRecord || cls.isInterface || cls.fields.isNotEmpty()) {
            result.add(cls)
        }
    }
}
