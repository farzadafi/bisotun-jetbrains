package com.bisotun

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil

class ModelExtractor(private val project: Project) {

    private fun isInContent(element: PsiElement): Boolean {
        val vf = element.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInContent(vf)
    }

    fun extractModels(method: PsiMethod): List<PsiElement> {
        val visited = linkedSetOf<String>()
        val result = mutableListOf<PsiElement>()

        method.returnType?.let { inspectTypeDeep(it, visited, result) }
        for (p in method.parameterList.parameters) {
            inspectTypeDeep(p.type, visited, result)
        }

        return result
    }

    private fun inspectTypeDeep(type: PsiType, visited: MutableSet<String>, result: MutableList<PsiElement>) {
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

                // باز کردن جنریک‌ها: مثلاً List<ChapterResponse> -> ChapterResponse
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
    }

    private fun extractClassModel(cls: PsiClass, visited: MutableSet<String>, result: MutableList<PsiElement>) {
        if (!isInContent(cls)) return

        val fqn = cls.qualifiedName ?: return
        if (!visited.add(fqn)) return

        val lowerName = cls.name?.lowercase() ?: ""

        // فیلتر کردن لایه‌های سرویس/کنترلر/ریپازیتوری
        if (lowerName.endsWith("service") || lowerName.endsWith("controller") || lowerName.endsWith("repository")) {
            return
        }

        // انتیتی‌ها، ریکوردها، اینترفیس‌ها (مثل Spring Projection) و کلاس‌های دیتا
        if (cls.isEnum || cls.isRecord || cls.isInterface || cls.fields.isNotEmpty()) {
            result.add(cls)
        }
    }
}
