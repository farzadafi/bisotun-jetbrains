package com.bisotun.go

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.language.FlowExtractionOutcome
import com.bisotun.language.GO_PLUGIN_REQUIRED_MESSAGE
import com.bisotun.language.LanguageFlowExtractor
import com.bisotun.language.LanguageKind
import com.bisotun.language.PsiFlowNodes
import com.goide.psi.GoArrayOrSliceType
import com.goide.psi.GoBlock
import com.goide.psi.GoCallExpr
import com.goide.psi.GoChannelType
import com.goide.psi.GoDeferStatement
import com.goide.psi.GoExprSwitchStatement
import com.goide.psi.GoFile
import com.goide.psi.GoForStatement
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.goide.psi.GoGoStatement
import com.goide.psi.GoIfStatement
import com.goide.psi.GoImportSpec
import com.goide.psi.GoInterfaceType
import com.goide.psi.GoMapType
import com.goide.psi.GoMethodDeclaration
import com.goide.psi.GoMethodSpec
import com.goide.psi.GoParamDefinition
import com.goide.psi.GoPointerType
import com.goide.psi.GoRangeClause
import com.goide.psi.GoReferenceExpression
import com.goide.psi.GoReturnStatement
import com.goide.psi.GoSelectStatement
import com.goide.psi.GoSendStatement
import com.goide.psi.GoStatement
import com.goide.psi.GoStructType
import com.goide.psi.GoType
import com.goide.psi.GoTypeReferenceExpression
import com.goide.psi.GoTypeSpec
import com.goide.psi.GoTypeSwitchStatement
import com.goide.psi.GoVarDefinition
import com.goide.psi.GoVarSpec
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.parentOfType

/**
 * Native Go strategy built on the official JetBrains Go plugin PSI (`org.jetbrains.plugins.go`).
 *
 * Go PSI is only shipped by the JetBrains Go plugin, which is bundled in GoLand. This class lives in
 * the `bisotun-go` content module, so the IDE loads it only when that plugin is installed. Without
 * it a `.go` file is plain text and [com.bisotun.language.FlowExtractionDispatcher] reports the
 * GoLand prerequisite instead of pretending to understand the file.
 *
 * Everything here is real PSI:
 *  - declarations come from [GoFile] / [GoFunctionOrMethodDeclaration] / [GoTypeSpec];
 *  - call sites are [GoCallExpr] references resolved through the plugin's own reference resolution
 *    ([GoReferenceExpression.resolve]), which also follows receivers, fields, constructor return
 *    types, same-package sibling files and imported packages;
 *  - media types come from [GoType.presentationText] and resolve to [GoTypeSpec] declarations.
 *
 * Name-only matching is never used: when the Go plugin cannot resolve a call (dynamic/interface
 * values, missing modules, reflection) the node is reported as [ResolutionStatus.UNRESOLVED] and is
 * not expanded.
 */
class GoFlowExtractor(private val project: Project) : LanguageFlowExtractor {

    private val log = Logger.getInstance(GoFlowExtractor::class.java)

    override val supportedLanguages: Set<LanguageKind> = setOf(LanguageKind.GO)

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val goFile = file as? GoFile
            ?: return FlowExtractionOutcome.failure(LanguageKind.GO, GO_PLUGIN_REQUIRED_MESSAGE)

        val caretOffset = editor.caretModel.offset.coerceIn(0, maxOf(goFile.textLength - 1, 0))
        val leaf = goFile.findElementAt(caretOffset)

        return try {
            val function = leaf?.parentOfType<GoFunctionOrMethodDeclaration>(withSelf = true)
            val typeSpec = if (function == null) leaf?.parentOfType<GoTypeSpec>(withSelf = true) else null
            val result = when {
                function != null -> flowOfFunction(function, maxDepth, includeModels)
                typeSpec != null -> flowOfType(goFile, typeSpec, maxDepth, includeModels)
                else -> flowOfFile(goFile, maxDepth, includeModels)
            }
            FlowExtractionOutcome(result, LanguageKind.GO)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Go PSI flow extraction failed", t)
            FlowExtractionOutcome.failure(
                LanguageKind.GO,
                "Could not analyze this Go file: ${t.message ?: "unknown error"}"
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // Flow assembly
    // -------------------------------------------------------------------------------------

    private fun flowOfFunction(
        function: GoFunctionOrMethodDeclaration,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowResult {
        val root = functionNode(function)
        val nodes = LinkedHashMap<String, FlowNode>()
        val expanded = mutableListOf<GoFunctionOrMethodDeclaration>()

        fun add(node: FlowNode) {
            if (nodes.size >= MAX_NODES || node.id == root.id) return
            nodes.putIfAbsent(node.id, node)
        }

        if (maxDepth > 0) {
            val body = function.block
            if (body != null) {
                for (statement in controlFlowStatements(body)) add(statementNode(statement))
                for (declaration in localDeclarations(body)) add(localDeclarationNode(declaration))
            }
            expanded.add(function)
            expandCalls(function, maxDepth, ::add) { expanded.add(it) }
        }

        val models = if (includeModels) {
            collectModels(expanded, emptyList(), function.containingFile)
        } else {
            emptyList()
        }
        return FlowResult(root, nodes.values.toList(), models, listOf(PSI_WARNING))
    }

    private fun flowOfType(
        file: GoFile,
        typeSpec: GoTypeSpec,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowResult {
        val root = typeNode(file, typeSpec)
        val nodes = LinkedHashMap<String, FlowNode>()

        fun add(node: FlowNode) {
            if (nodes.size >= MAX_NODES || node.id == root.id) return
            nodes.putIfAbsent(node.id, node)
        }

        if (maxDepth > 0) {
            for (method in typeSpec.methods.sortedBy { it.textOffset }) add(functionNode(method))
            for (owner in typeSpec.allMethods) {
                if (owner is GoMethodDeclaration) add(functionNode(owner))
                if (owner is GoMethodSpec) add(interfaceMethodNode(owner))
            }
        }

        val models = if (includeModels) collectModels(emptyList(), listOf(typeSpec), file) else emptyList()
        return FlowResult(root, nodes.values.toList(), models, listOf(PSI_WARNING))
    }

    private fun flowOfFile(file: GoFile, maxDepth: Int, includeModels: Boolean): FlowResult {
        val root = packageNode(file)
        val nodes = LinkedHashMap<String, FlowNode>()

        fun add(node: FlowNode) {
            if (nodes.size >= MAX_NODES || node.id == root.id) return
            nodes.putIfAbsent(node.id, node)
        }

        if (maxDepth > 0) {
            for (importSpec in file.imports) add(importNode(importSpec))
            for (typeSpec in file.types.sortedBy { it.textOffset }) add(typeNode(file, typeSpec))
            for (function in file.functions.sortedBy { it.textOffset }) add(functionNode(function))
        }

        val models = if (includeModels) {
            collectModels(emptyList(), file.types.toList(), file)
        } else {
            emptyList()
        }
        return FlowResult(root, nodes.values.toList(), models, listOf(PSI_WARNING))
    }

    /**
     * Breadth-first expansion over resolved callees. A callee is emitted (and expanded) while
     * `depth <= maxDepth`, exactly like the other language strategies.
     */
    private fun expandCalls(
        root: GoFunctionOrMethodDeclaration,
        maxDepth: Int,
        add: (FlowNode) -> Unit,
        onVisited: (GoFunctionOrMethodDeclaration) -> Unit
    ) {
        val rootId = PsiFlowNodes.nodeId(root)
        val queued = mutableSetOf<String>()
        var frontier = listOf(root)
        var depth = 1
        while (depth <= maxDepth && frontier.isNotEmpty()) {
            val next = mutableListOf<GoFunctionOrMethodDeclaration>()
            for (declaration in frontier) {
                val body = declaration.block ?: continue
                for (call in PsiTreeUtil.findChildrenOfType(body, GoCallExpr::class.java)) {
                    when (val resolved = resolveCall(call)) {
                        is GoFunctionOrMethodDeclaration -> {
                            if (isNoiseDeclaration(resolved)) continue
                            val node = functionNode(resolved)
                            add(node)
                            if (resolved.block != null && node.id != rootId && depth < maxDepth &&
                                queued.add(node.id)
                            ) {
                                onVisited(resolved)
                                next.add(resolved)
                            }
                        }

                        is GoMethodSpec -> add(interfaceMethodNode(resolved))

                        else -> if (!isUnresolvedNoise(call)) add(unresolvedNode(call))
                    }
                }
            }
            frontier = next
            depth++
        }
    }

    private fun resolveCall(call: GoCallExpr): PsiElement? {
        val expression = call.expression as? GoReferenceExpression ?: return null
        return expression.resolve()
    }

    // -------------------------------------------------------------------------------------
    // Declarations and control flow
    // -------------------------------------------------------------------------------------

    private fun controlFlowStatements(body: GoBlock): List<GoStatement> =
        PsiTreeUtil.findChildrenOfType(body, GoStatement::class.java)
            .filter { statement -> statement.isFlowStatement() }
            .filter { statement -> !isInsideNestedDeclaration(statement, body) }

    private fun GoStatement.isFlowStatement(): Boolean = when (this) {
        is GoIfStatement,
        is GoForStatement,
        is GoExprSwitchStatement,
        is GoTypeSwitchStatement,
        is GoSelectStatement,
        is GoReturnStatement,
        is GoDeferStatement,
        is GoGoStatement,
        is GoSendStatement -> true

        else -> false
    }

    private fun isInsideNestedDeclaration(element: PsiElement, body: GoBlock): Boolean {
        var parent = element.parent
        while (parent != null && parent != body) {
            if (parent is GoFunctionOrMethodDeclaration) return true
            parent = parent.parent
        }
        return false
    }

    private fun localDeclarations(body: GoBlock): List<GoVarDefinition> =
        PsiTreeUtil.findChildrenOfType(body, GoVarDefinition::class.java)
            .filter { declaration -> declaration.parentOfType<GoRangeClause>(withSelf = true) == null }
            .filter { declaration -> !isInsideNestedDeclaration(declaration, body) }

    // -------------------------------------------------------------------------------------
    // Models
    // -------------------------------------------------------------------------------------

    private fun collectModels(
        functions: List<GoFunctionOrMethodDeclaration>,
        explicitTypes: List<GoTypeSpec>,
        rootFile: GoFile
    ): List<FlowNode> {
        val found = LinkedHashMap<String, FlowNode>()
        val queue = ArrayDeque<GoTypeSpec>()
        val visited = mutableSetOf<String>()

        fun enqueue(spec: GoTypeSpec?) {
            if (spec == null || found.size >= MAX_MODELS) return
            val id = PsiFlowNodes.nodeId(spec)
            if (!visited.add(id)) return
            found[id] = modelNode(rootFile, spec)
            queue.addLast(spec)
        }

        explicitTypes.forEach(::enqueue)

        for (function in functions) {
            val signature = function.signature ?: continue
            signature.parameters.parameterDeclarationList.forEach { enqueue(typeSpecOf(it.type)) }
            signature.result?.let { result ->
                if (result.type != null) enqueue(typeSpecOf(result.type))
                result.parameters?.parameterDeclarationList?.forEach { enqueue(typeSpecOf(it.type)) }
            }
            if (function is GoMethodDeclaration) enqueue(typeSpecOf(function.receiverType))

            val body = function.block
            if (body != null) {
                for (reference in PsiTreeUtil.findChildrenOfType(body, GoTypeReferenceExpression::class.java)) {
                    enqueue(reference.resolve() as? GoTypeSpec)
                }
                for (variable in PsiTreeUtil.findChildrenOfType(body, GoVarDefinition::class.java)) {
                    enqueue(typeSpecOf(variable.getGoTypeInner(null)))
                }
            }
        }

        // Composition stays visible: follow embedded types, fields and interface base types.
        while (queue.isNotEmpty() && found.size < MAX_MODELS) {
            val spec = queue.removeFirst()
            when (val type = spec.specType.type) {
                is GoStructType -> {
                    for (field in type.fieldDeclarationList) {
                        enqueue(typeSpecOf(field.type))
                        field.anonymousFieldDefinition?.let { enqueue(typeSpecOf(it.type)) }
                    }
                }

                is GoInterfaceType -> {
                    for (base in type.baseTypesReferences) enqueue(base.resolve() as? GoTypeSpec)
                }

                else -> Unit
            }
        }
        return found.values.toList()
    }

    /** Resolves a (possibly wrapped) Go type to its declaration without inventing one. */
    private fun typeSpecOf(type: GoType?): GoTypeSpec? {
        if (type == null) return null
        (type.typeReferenceExpression?.resolve() as? GoTypeSpec)?.let { return it }
        (type.contextlessResolve() as? GoTypeSpec)?.let { return it }
        return when (type) {
            is GoPointerType -> typeSpecOf(type.type)
            is GoArrayOrSliceType -> typeSpecOf(type.type)
            is GoChannelType -> typeSpecOf(type.type)
            is GoMapType -> {
                typeSpecOf(type.valueType) ?: typeSpecOf(type.keyType)
            }

            else -> null
        }
    }

    // -------------------------------------------------------------------------------------
    // Noise filtering
    // -------------------------------------------------------------------------------------

    /** Built-ins, generated files and library/SDK declarations are not project flow steps. */
    private fun isNoiseDeclaration(declaration: GoFunctionOrMethodDeclaration): Boolean {
        val file = declaration.containingFile as? GoFile ?: return false
        if (file.isGenerated) return true
        val virtualFile = file.virtualFile ?: return false
        if (!virtualFile.isInLocalFileSystem) return false
        if (virtualFile.path.contains("/vendor/")) return true
        return !ProjectFileIndex.getInstance(project).isInContent(virtualFile)
    }

    /**
     * An unresolved call is only noise when it is clearly outside the project: a built-in or a
     * function of a standard-library import. Project-local unresolved calls are always reported.
     */
    private fun isUnresolvedNoise(call: GoCallExpr): Boolean {
        val reference = call.expression as? GoReferenceExpression ?: return false
        val qualifier = reference.qualifier?.text
        val file = call.containingFile as? GoFile
        if (qualifier == null) {
            val name = reference.identifier.text
            return name in GO_BUILTINS
        }
        val importSpec = file?.imports?.firstOrNull { localNameOf(it) == qualifier } ?: return false
        return isStandardLibraryPath(importSpec.path)
    }

    private fun localNameOf(spec: GoImportSpec): String? =
        spec.alias ?: spec.path.substringAfterLast('/').takeIf { it.isNotEmpty() }

    // -------------------------------------------------------------------------------------
    // Node construction
    // -------------------------------------------------------------------------------------

    private fun packageNode(file: GoFile): FlowNode {
        val clause = file.getPackage() ?: file
        val packageName = file.packageName ?: "<unknown>"
        return PsiFlowNodes.fromElement(
            project,
            clause,
            LanguageKind.GO,
            NodeKind.UNKNOWN,
            ResolutionStatus.RESOLVED,
            "package $packageName"
        )
    }

    private fun importNode(spec: GoImportSpec): FlowNode {
        val resolved = spec.references.any { it.resolve() != null } ||
            spec.isForSideEffects ||
            isStandardLibraryPath(spec.path)
        val alias = spec.alias?.let { "$it " } ?: ""
        return PsiFlowNodes.fromElement(
            project,
            spec,
            LanguageKind.GO,
            NodeKind.UNKNOWN,
            if (resolved) ResolutionStatus.RESOLVED else ResolutionStatus.UNRESOLVED,
            "import $alias\"${spec.path}\""
        )
    }

    private fun typeNode(file: GoFile, spec: GoTypeSpec): FlowNode {
        val packageName = (spec.containingFile as? GoFile)?.packageName ?: file.packageName ?: ""
        val name = spec.name ?: "<anonymous>"
        val display = if (packageName.isEmpty()) name else "$packageName.$name"
        return PsiFlowNodes.fromElement(
            project,
            spec,
            LanguageKind.GO,
            NodeKind.MODEL,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun modelNode(rootFile: GoFile, spec: GoTypeSpec): FlowNode {
        val declaringFile = spec.containingFile as? GoFile
        val samePackage = declaringFile?.packageName != null &&
            declaringFile.packageName == rootFile.packageName &&
            declaringFile.virtualFile?.parent?.path == rootFile.virtualFile?.parent?.path
        val name = spec.name ?: "<anonymous>"
        val display = if (samePackage) name else {
            val packageName = declaringFile?.packageName
            if (packageName.isNullOrEmpty()) name else "$packageName.$name"
        }
        return PsiFlowNodes.fromElement(
            project,
            spec,
            LanguageKind.GO,
            NodeKind.MODEL,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun functionNode(declaration: GoFunctionOrMethodDeclaration): FlowNode =
        PsiFlowNodes.fromElement(
            project,
            declaration,
            LanguageKind.GO,
            if (declaration is GoMethodDeclaration) NodeKind.METHOD else NodeKind.FUNCTION,
            ResolutionStatus.RESOLVED,
            functionDisplayName(declaration)
        )

    private fun interfaceMethodNode(spec: GoMethodSpec): FlowNode {
        val owner = spec.parentOfType<GoTypeSpec>(withSelf = true)?.name
        val name = spec.name ?: "<anonymous>"
        val display = if (owner != null) "$owner.$name" else name
        return PsiFlowNodes.fromElement(
            project,
            spec,
            LanguageKind.GO,
            NodeKind.METHOD,
            ResolutionStatus.RESOLVED,
            display + signatureSuffix(spec.signature)
        )
    }

    private fun statementNode(statement: GoStatement): FlowNode = PsiFlowNodes.fromElement(
        project,
        statement,
        LanguageKind.GO,
        NodeKind.UNKNOWN,
        ResolutionStatus.RESOLVED,
        statementDisplayName(statement)
    )

    private fun localDeclarationNode(declaration: GoVarDefinition): FlowNode {
        val name = declaration.name ?: "<anonymous>"
        val type = declaration.getGoTypeInner(null)?.presentationText?.trim()
        val spec = declaration.parentOfType<GoVarSpec>(withSelf = true)
        val initializer = spec?.expressionList?.firstOrNull()?.text?.let(::collapseWhitespace)
        val display = when {
            !type.isNullOrEmpty() -> "$name $type"
            !initializer.isNullOrEmpty() -> "$name := $initializer"
            else -> name
        }
        return PsiFlowNodes.fromElement(
            project,
            declaration,
            LanguageKind.GO,
            NodeKind.UNKNOWN,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun unresolvedNode(call: GoCallExpr): FlowNode {
        val callee = collapseWhitespace(call.text.substringBefore('(')).take(MAX_UNRESOLVED_LENGTH)
        return PsiFlowNodes.fromElement(
            project,
            call,
            LanguageKind.GO,
            NodeKind.UNKNOWN,
            ResolutionStatus.UNRESOLVED,
            "$callee(...)"
        )
    }

    // -------------------------------------------------------------------------------------
    // Display names
    // -------------------------------------------------------------------------------------

    private fun functionDisplayName(declaration: GoFunctionOrMethodDeclaration): String {
        val name = declaration.name ?: "<anonymous>"
        val receiver = (declaration as? GoMethodDeclaration)?.receiverType?.presentationText
            ?.trim()
            ?.removePrefix("*")
        val head = if (receiver.isNullOrEmpty()) name else "$receiver.$name"
        return head + signatureSuffix(declaration.signature)
    }

    private fun signatureSuffix(signature: com.goide.psi.GoSignature?): String {
        if (signature == null) return "()"
        val parameters = signature.parameters.parameterDeclarationList.mapNotNull { declaration ->
            val type = declaration.type?.presentationText?.trim() ?: return@mapNotNull null
            val names = declaration.paramDefinitionList.mapNotNull { it.name }.filter { it != "_" }
            if (names.isEmpty()) type else "${names.joinToString(", ")} $type"
        }.joinToString(", ")
        val results = resultTypes(signature)
        val resultText = when (results.size) {
            0 -> ""
            1 -> " ${results.single()}"
            else -> " (${results.joinToString(", ")})"
        }
        return "($parameters)$resultText"
    }

    private fun resultTypes(signature: com.goide.psi.GoSignature): List<String> {
        val result = signature.result ?: return emptyList()
        if (result.isVoid) return emptyList()
        result.type?.presentationText?.trim()?.takeIf { it.isNotEmpty() }?.let { return listOf(it) }
        val parameters = result.parameters ?: return emptyList()
        return parameters.parameterDeclarationList.mapNotNull { it.type?.presentationText?.trim() }
    }

    private fun statementDisplayName(statement: GoStatement): String {
        val raw = statement.text
        val header = when (statement) {
            is GoReturnStatement -> raw
            is GoDeferStatement -> raw
            is GoGoStatement -> raw
            is GoSendStatement -> raw
            else -> raw.substringBefore('{')
        }
        val collapsed = collapseWhitespace(header).trim().removeSuffix("{").trim()
        return if (collapsed.length > MAX_STATEMENT_LENGTH) {
            collapsed.take(MAX_STATEMENT_LENGTH - 3) + "..."
        } else {
            collapsed
        }
    }

    companion object {
        internal const val MAX_NODES = 300
        internal const val MAX_MODELS = 40
        private const val MAX_STATEMENT_LENGTH = 140
        private const val MAX_UNRESOLVED_LENGTH = 80

        private const val PSI_WARNING =
            "⚠ Go extraction uses native Go PSI from the JetBrains Go plugin. Calls are resolved " +
                "through Go references; interface calls stay at the interface declaration, and " +
                "dynamic or unresolved targets are reported as unresolved."

        private val GO_BUILTINS = setOf(
            "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len", "make",
            "max", "min", "new", "panic", "print", "println", "real", "recover"
        )
    }
}

/**
 * A standard-library import path has no dot in its first segment (`fmt`, `net/http`, `strings`).
 * Used only for cosmetic filtering of environment-dependent unresolved calls; project-local
 * packages always carry a domain (`example.com/project/pkg`).
 */
internal fun isStandardLibraryPath(path: String): Boolean {
    val first = path.substringBefore('/')
    return first.isNotEmpty() && !first.contains('.')
}

internal fun collapseWhitespace(value: String): String = value.replace(Regex("\\s+"), " ").trim()
