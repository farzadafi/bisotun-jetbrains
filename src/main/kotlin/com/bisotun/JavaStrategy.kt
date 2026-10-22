package com.bisotun

import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

val JAVA_PRIMITIVE_TYPES: Set<String> = setOf(
    "void", "boolean", "byte", "char", "short", "int", "long", "float", "double",
    "Boolean", "Byte", "Character", "Short", "Integer", "Long", "Float", "Double",
    "String", "Object", "Class", "List", "Set", "Map", "Collection", "Optional",
    "Page", "Pageable", "Sort", "ResponseEntity", "Stream", "Arrays", "ArrayList",
    "HashMap", "HashSet", "UUID", "LocalDate", "LocalDateTime", "Instant", "Date",
    "BigDecimal", "BigInteger", "CompletableFuture", "Mono", "Flux",
    "Override", "Transactional", "Autowired", "Component", "Service", "Repository",
    "Controller", "RestController", "Bean", "Configuration"
)

class JavaStrategy : LanguageStrategy {
    override val languageId: String = "java"

    override fun supports(languageId: String): Boolean = languageId.equals("java", ignoreCase = true)

    override fun extractTypeCandidates(code: String): List<String> {
        val codeWithoutAnnotations = code.replace(Regex("@[A-Za-z0-9_]+"), " ")
        val regex = Regex("\\b([A-Z][a-zA-Z0-9_]+)\\b")
        return regex.findAll(codeWithoutAnnotations)
            .map { it.groupValues[1] }
            .filter { it !in JAVA_PRIMITIVE_TYPES }
            .distinct()
            .toList()
    }

    override fun findEnclosingScope(file: PsiFile, position: Int): EnclosingScope? {
        if (!supports(file.language.id)) return null
        val element = file.findElementAt(position) ?: return null

        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
            ?: return null
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)

        val startLine = (file.viewProvider.document?.getLineNumber(method.textRange.startOffset) ?: 0) + 1
        val endLine = (file.viewProvider.document?.getLineNumber(method.textRange.endOffset) ?: 0) + 1

        return EnclosingScope(
            className = clazz?.name ?: "",
            methodName = method.name ?: "",
            startLine = startLine,
            endLine = endLine
        )
    }

    override fun resolveModelDefinition(
        file: PsiFile,
        typeName: String,
        methodRange: PsiElement
    ): ModelDefinition? {
        if (!supports(file.language.id)) return null

        // Fast path: any class in same file
        val classes = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java)
        val cls = classes.firstOrNull { it.name == typeName } ?: return null

        return ModelDefinition(
            name = cls.name ?: typeName,
            filePath = file.virtualFile?.path ?: file.name,
            code = cls.text ?: ""
        )
    }

    override fun resolveImplementationLocation(file: PsiFile, position: Int): ImplementationLocation? {
        // نیاز به Search API دارد (ReferencesSearch / DefinitionsScopedSearch / etc).
        // فعلاً برای سبز شدن build و جلوگیری از APIهای اشتباه، null برمی‌گردانیم.
        return null
    }

    override fun resolveFallbackCalls(file: PsiFile, methodRange: PsiElement): List<FallbackCall> {
        if (!supports(file.language.id)) return emptyList()

        val calls = mutableListOf<FallbackCall>()
        val methodText = methodRange.text ?: return emptyList()

        // this.service.method(...)
        val chainedRegex =
            Regex("\\bthis\\.([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(")
        chainedRegex.findAll(methodText).forEach { match ->
            calls.add(
                FallbackCall(
                    psiElement = methodRange,
                    name = match.groupValues[2],
                    detail = match.groupValues[1]
                )
            )
        }

        // receiver.method(...)
        val memberRegex =
            Regex("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(")
        memberRegex.findAll(methodText).forEach { match ->
            val recv = match.groupValues[1]
            if (recv != "this" && recv != "console" && recv != "Math" &&
                recv != "JSON" && recv != "Promise" && recv != "new"
            ) {
                calls.add(
                    FallbackCall(
                        psiElement = methodRange,
                        name = match.groupValues[2],
                        detail = recv
                    )
                )
            }
        }

        // this.method(...)
        val directRegex = Regex("\\bthis\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(")
        directRegex.findAll(methodText).forEach { match ->
            calls.add(
                FallbackCall(
                    psiElement = methodRange,
                    name = match.groupValues[1]
                )
            )
        }

        return calls
    }
}
