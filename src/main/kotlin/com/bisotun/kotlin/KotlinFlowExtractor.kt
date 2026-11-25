package com.bisotun.kotlin

import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.language.FlowExtractionOutcome
import com.bisotun.language.KOTLIN_PLUGIN_REQUIRED_MESSAGE
import com.bisotun.language.LanguageFlowExtractor
import com.bisotun.language.LanguageKind
import com.bisotun.language.LanguageKindDetector
import com.bisotun.language.PsiFlowNodes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Kotlin strategy. Kotlin PSI provides the structure; the Kotlin plugin itself provides semantic
 * resolution (`KtReference.mainReference.resolve()`), which is the same resolution the IDE uses for
 * navigation and works identically in K1 and K2 modes.
 *
 * Why not the Analysis API directly: the `KaSession` API is marked experimental and its binary
 * signatures are not stable across the plugin's supported IDE range (2024.1-2026.1). The reference
 * API used here has the same binary signature in the 2024.1.4, 2025.3 and 2026.1 Kotlin plugins, so
 * one build is verifiable across the whole matrix while still resolving overloads, extension
 * functions, constructors and cross-file calls semantically.
 *
 * Kotlin PSI is only present when the JetBrains Kotlin plugin is installed, so this class lives in
 * the `bisotun-kotlin` content module and is never loaded in an IDE without it.
 */
class KotlinFlowExtractor(
    private val project: Project,
    private val strictWiring: Boolean = false
) : LanguageFlowExtractor {

    private val log = Logger.getInstance(KotlinFlowExtractor::class.java)

    override val supportedLanguages: Set<LanguageKind> = setOf(LanguageKind.KOTLIN)

    override fun extractFlow(
        file: PsiFile,
        editor: Editor,
        maxDepth: Int,
        includeModels: Boolean
    ): FlowExtractionOutcome {
        val languageKind = LanguageKindDetector.detect(file)
        if (file !is KtFile) {
            return FlowExtractionOutcome.failure(languageKind, KOTLIN_PLUGIN_REQUIRED_MESSAGE)
        }

        val caretOffset = editor.caretModel.offset.coerceIn(0, maxOf(file.textLength - 1, 0))
        val leaf = try {
            file.findElementAt(caretOffset)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            null
        }

        return try {
            val function = leaf?.parentOfTypeStrict<KtNamedFunction>()
            val property = if (function == null) leaf?.propertyLambdaContaining() else null
            val klass = if (function == null && property == null) leaf?.parentOfTypeStrict<KtClassOrObject>() else null
            val result = when {
                function != null -> flowOfFunction(function, maxDepth, includeModels, emptyList())
                property != null -> flowOfFunction(
                    function = null,
                    maxDepth = maxDepth,
                    includeModels = includeModels,
                    property = listOf(property)
                )

                klass is KtClass -> flowOfClass(klass, maxDepth, includeModels)
                klass != null -> flowOfObject(klass, maxDepth, includeModels)
                else -> return FlowExtractionOutcome.failure(languageKind, NO_SCOPE_MESSAGE)
            }
            FlowExtractionOutcome(result, LanguageKind.KOTLIN)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Kotlin PSI flow extraction failed", t)
            FlowExtractionOutcome.failure(
                LanguageKind.KOTLIN,
                "Could not analyze this Kotlin file: ${t.message ?: "unknown error"}"
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // Flow assembly
    // -------------------------------------------------------------------------------------

    private fun flowOfFunction(
        function: KtNamedFunction?,
        maxDepth: Int,
        includeModels: Boolean,
        property: List<KtProperty>
    ): FlowResult {
        val propertyLambda = property.singleOrNull()
        val root = when {
            function != null -> declarationNode(function)
            propertyLambda != null -> propertyNode(propertyLambda)
            else -> error("no Kotlin root")
        }
        val visited = linkedSetOf(root.id)
        val nodes = LinkedHashMap<String, FlowNode>()
        val warnings = mutableListOf(KOTLIN_WARNING)
        val expanded = mutableListOf<KtNamedFunction>()
        val constructorTypes = LinkedHashMap<String, KtClassOrObject>()

        fun add(node: FlowNode) {
            if (nodes.size >= MAX_NODES || node.id == root.id) return
            if (visited.add(node.id)) nodes[node.id] = node
        }

        if (maxDepth > 0) {
            val body = function?.bodyExpression ?: propertyLambda?.initializer
            if (body != null) {
                expand(
                    body = body,
                    maxDepth = maxDepth,
                    add = ::add,
                    onExpanded = { expanded.add(it) },
                    onConstructor = { constructorTypes[PsiFlowNodes.nodeId(it)] = it },
                    warnings = warnings
                )
            }
        }

        val models = if (includeModels) {
            collectModels(expanded, constructorTypes.values.toList(), rootOwner(function, propertyLambda))
        } else {
            emptyList()
        }
        return FlowResult(root, nodes.values.toList(), models, warnings)
    }

    private fun flowOfClass(klass: KtClass, maxDepth: Int, includeModels: Boolean): FlowResult {
        val root = classNode(klass)
        val nodes = LinkedHashMap<String, FlowNode>()
        if (maxDepth > 0) {
            val members = memberDeclarations(klass)
            for (member in members) {
                val node = nodeForMember(member) ?: continue
                if (node.id == root.id || nodes.size >= MAX_NODES) continue
                nodes.putIfAbsent(node.id, node)
            }
        }
        val models = if (includeModels) {
            collectModels(emptyList(), listOf(klass), klass)
        } else {
            emptyList()
        }
        return FlowResult(root, nodes.values.toList(), models, listOf(KOTLIN_WARNING))
    }

    private fun flowOfObject(klass: KtClassOrObject, maxDepth: Int, includeModels: Boolean): FlowResult {
        val root = classNode(klass)
        val nodes = LinkedHashMap<String, FlowNode>()
        if (maxDepth > 0) {
            for (member in memberDeclarations(klass)) {
                val node = nodeForMember(member) ?: continue
                if (node.id == root.id || nodes.size >= MAX_NODES) continue
                nodes.putIfAbsent(node.id, node)
            }
        }
        val models = if (includeModels) {
            collectModels(emptyList(), listOf(klass), klass)
        } else {
            emptyList()
        }
        return FlowResult(root, nodes.values.toList(), models, listOf(KOTLIN_WARNING))
    }

    /**
     * Breadth-first expansion over semantically resolved callees. A callee is emitted and expanded
     * while `depth <= maxDepth`; the visited set prevents cycles. Calls inside nested named
     * declarations are ignored (they belong to the nested function, not this body), while lambda
     * bodies stay part of the enclosing flow because they are executed by the containing call.
     */
    private fun expand(
        body: PsiElement,
        maxDepth: Int,
        add: (FlowNode) -> Unit,
        onExpanded: (KtNamedFunction) -> Unit,
        onConstructor: (KtClassOrObject) -> Unit,
        warnings: MutableList<String>
    ) {
        val rootBody = body
        var frontier = listOf(rootBody)
        var depth = 1
        while (depth <= maxDepth && frontier.isNotEmpty()) {
            val next = mutableListOf<PsiElement>()
            for (currentBody in frontier) {
                for (site in callSites(currentBody)) {
                    val resolved = resolve(site)
                    when (resolved) {
                        is KtNamedFunction -> {
                            if (isLibraryDeclaration(resolved)) continue
                            val node = declarationNode(resolved)
                            add(node)
                            if (resolved.bodyExpression != null) {
                                if (depth < maxDepth) {
                                    onExpanded(resolved)
                                    next.add(resolved.bodyExpression!!)
                                }
                            } else {
                                addInterfaceCandidates(resolved, add, warnings)
                            }
                        }

                        is KtConstructor<*> -> {
                            val owner = resolved.containingClassOrObject ?: continue
                            if (isLibraryDeclaration(owner)) continue
                            add(constructorNode(owner, site))
                            onConstructor(owner)
                        }

                        is KtClassOrObject -> {
                            if (isLibraryDeclaration(resolved)) continue
                            add(constructorNode(resolved, site))
                            onConstructor(resolved)
                        }

                        is KtProperty -> {
                            if (isLibraryDeclaration(resolved)) continue
                            add(propertyNode(resolved))
                        }

                        is PsiMethod -> {
                            if (isLibraryElement(resolved)) continue
                            add(javaMethodNode(resolved))
                        }

                        else -> if (!isUnresolvedNoise(site)) add(unresolvedNode(site))
                    }
                }
            }
            frontier = next
            depth++
        }
    }

    // -------------------------------------------------------------------------------------
    // Call sites and resolution
    // -------------------------------------------------------------------------------------

    private fun callSites(body: PsiElement): List<PsiElement> {
        val sites = LinkedHashMap<String, PsiElement>()
        fun consider(element: PsiElement) {
            if (isInsideNestedDeclaration(element, body)) return
            sites.putIfAbsent("${element.textRange.startOffset}-${element.textRange.endOffset}", element)
        }
        // findChildrenOfType excludes the receiver itself, and an expression-bodied function's
        // body can be the call/callable reference; include it when it matches.
        if (body is KtCallExpression || body is KtCallableReferenceExpression || body is KtBinaryExpression) {
            consider(body)
        }
        PsiTreeUtil.findChildrenOfType(body, KtCallExpression::class.java).forEach(::consider)
        PsiTreeUtil.findChildrenOfType(body, KtCallableReferenceExpression::class.java).forEach(::consider)
        PsiTreeUtil.findChildrenOfType(body, KtBinaryExpression::class.java)
            .filter { it.operationReference.resolveInfix() }
            .forEach(::consider)
        return sites.values.toList()
    }

    /** Semantic resolution through the Kotlin plugin; never a name lookup. */
    private fun resolve(site: PsiElement): PsiElement? = try {
        when (site) {
            is KtCallExpression -> site.calleeExpression?.mainReference?.resolve()
            is KtCallableReferenceExpression -> site.callableReference.mainReference.resolve()
            is KtBinaryExpression -> site.operationReference.mainReference.resolve()
            else -> null
        }
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (t: Throwable) {
        log.debug("Kotlin reference resolution failed", t)
        null
    }

    private fun org.jetbrains.kotlin.psi.KtOperationReferenceExpression.resolveInfix(): Boolean = try {
        getReferencedNameElement().text in INFIX_LIKE && mainReference.resolve() is KtNamedFunction
    } catch (t: Throwable) {
        false
    }

    private fun isInsideNestedDeclaration(element: PsiElement, body: PsiElement): Boolean {
        var parent: PsiElement? = element
        while (parent != null && parent !== body) {
            if (parent is KtNamedFunction || parent is KtClassOrObject) return true
            parent = parent.parent
        }
        return false
    }

    /**
     * Interface methods have no body. Their implementations are reported as candidates when the
     * project index can prove they inherit the interface. They are never claimed as the runtime
     * target, and strict wiring omits them entirely.
     */
    private fun addInterfaceCandidates(
        declaration: KtNamedFunction,
        add: (FlowNode) -> Unit,
        warnings: MutableList<String>
    ) {
        if (strictWiring) return
        val owner = declaration.containingClassOrObject as? KtClass ?: return
        if (!owner.isInterface()) return
        val candidates = try {
            val light = owner.toLightClass() ?: return
            ClassInheritorsSearch.search(light, GlobalSearchScope.projectScope(project), true)
                .findAll()
                .mapNotNull { it.navigationElement }
                .filterIsInstance<KtClassOrObject>()
                .sortedBy { it.textOffset }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (t: Throwable) {
            log.debug("Kotlin interface inheritor search unavailable", t)
            emptyList()
        }

        val implementations = candidates.mapNotNull { candidate ->
            candidate.declarations
                .filterIsInstance<KtNamedFunction>()
                .firstOrNull { member ->
                    member.name == declaration.name &&
                        member.valueParameters.size == declaration.valueParameters.size &&
                        member.bodyExpression != null
                }
        }.distinctBy { PsiFlowNodes.nodeId(it) }

        for (implementation in implementations) {
            add(
                PsiFlowNodes.fromElement(
                    project,
                    implementation,
                    LanguageKind.KOTLIN,
                    NodeKind.METHOD,
                    ResolutionStatus.RESOLVED,
                    declarationDisplayName(implementation) + CANDIDATE_SUFFIX
                )
            )
        }
        if (implementations.isNotEmpty()) {
            val candidateNames = implementations.joinToString(", ") { declarationDisplayName(it) }
            warnings.add(
                "Kotlin interface call `${declaration.name}` resolved to the interface declaration. " +
                    "Candidate implementations (not guaranteed runtime targets): $candidateNames."
            )
        }
    }

    // -------------------------------------------------------------------------------------
    // Models
    // -------------------------------------------------------------------------------------

    private fun collectModels(
        functions: List<KtNamedFunction>,
        explicitTypes: List<KtClassOrObject>,
        rootOwner: KtClassOrObject?
    ): List<FlowNode> {
        val found = LinkedHashMap<String, FlowNode>()
        val queue = ArrayDeque<KtClassOrObject>()
        val visited = mutableSetOf<String>()

        fun enqueue(type: KtClassOrObject?) {
            if (type == null || found.size >= MAX_MODELS) return
            if (rootOwner != null && PsiFlowNodes.nodeId(type) == PsiFlowNodes.nodeId(rootOwner)) return
            if (isLibraryDeclaration(type)) return
            val id = PsiFlowNodes.nodeId(type)
            if (!visited.add(id)) return
            found[id] = modelNode(type)
            queue.addLast(type)
        }

        explicitTypes.forEach { enqueue(it) }

        for (function in functions) {
            enqueue(resolveType(function.typeReference))
            enqueue(resolveType(function.receiverTypeReference))
            function.valueParameters.forEach { enqueue(resolveType(it.typeReference)) }
            val body = function.bodyExpression ?: continue
            for (reference in PsiTreeUtil.findChildrenOfType(body, KtTypeReference::class.java)) {
                enqueue(resolveType(reference))
            }
            for (property in PsiTreeUtil.findChildrenOfType(body, KtProperty::class.java)) {
                enqueue(resolveType(property.typeReference))
            }
        }

        // Composition stays visible one level at a time: constructor parameters and declared
        // properties of the collected models. Standard-library types are filtered above.
        while (queue.isNotEmpty() && found.size < MAX_MODELS) {
            val type: KtClassOrObject = queue.removeFirst()
            type.primaryConstructorParameters.forEach { enqueue(resolveType(it.typeReference)) }
            PsiTreeUtil.findChildrenOfType(type, KtProperty::class.java).forEach { property ->
                enqueue(resolveType(property.typeReference))
            }
        }
        return found.values.toList()
    }

    private fun resolveType(reference: KtTypeReference?): KtClassOrObject? {
        val element = reference?.typeElement ?: return null
        val userTypes = PsiTreeUtil.findChildrenOfType(element, KtUserType::class.java)
        val candidates = (listOfNotNull(element as? KtUserType) + userTypes)
            .sortedByDescending { it.textLength }
        for (userType in candidates) {
            val resolved = try {
                userType.referenceExpression?.mainReference?.resolve()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (resolved is KtClassOrObject && !isLibraryDeclaration(resolved)) return resolved
        }
        return null
    }

    private fun rootOwner(function: KtNamedFunction?, property: KtProperty?): KtClassOrObject? =
        function?.containingClassOrObject ?: property?.containingClassOrObject

    // -------------------------------------------------------------------------------------
    // Node construction
    // -------------------------------------------------------------------------------------

    private fun declarationNode(function: KtNamedFunction): FlowNode = PsiFlowNodes.fromElement(
        project,
        function,
        LanguageKind.KOTLIN,
        if (function.containingClassOrObject != null) NodeKind.METHOD else NodeKind.FUNCTION,
        ResolutionStatus.RESOLVED,
        declarationDisplayName(function)
    )

    private fun propertyNode(property: KtProperty, displayName: String = propertyDisplayName(property)): FlowNode =
        PsiFlowNodes.fromElement(
            project,
            property,
            LanguageKind.KOTLIN,
            NodeKind.FUNCTION,
            ResolutionStatus.RESOLVED,
            displayName
        )

    private fun propertyDisplayName(property: KtProperty): String {
        val owner = property.containingClassOrObject
        val name = property.name ?: "<anonymous>"
        return if (owner != null) "${owner.name}.$name()" else "$name()"
    }

    private fun constructorNode(type: KtClassOrObject, call: PsiElement): FlowNode {
        val arguments = when (call) {
            is KtCallExpression -> call.valueArguments.joinToString(", ") { it.text }
            else -> ""
        }
        val label = type.name ?: "<anonymous>"
        val display = "$label($arguments)"
        return PsiFlowNodes.fromElement(
            project,
            type,
            LanguageKind.KOTLIN,
            NodeKind.METHOD,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun classNode(type: KtClassOrObject): FlowNode {
        val packageName = (type.containingFile as? KtFile)?.packageFqName?.asString()
        val name = type.name ?: "<anonymous>"
        val display = if (packageName.isNullOrEmpty()) name else "$packageName.$name"
        return PsiFlowNodes.fromElement(
            project,
            type,
            LanguageKind.KOTLIN,
            NodeKind.MODEL,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun modelNode(type: KtClassOrObject): FlowNode {
        val packageName = (type.containingFile as? KtFile)?.packageFqName?.asString()
        val name = type.name ?: "<anonymous>"
        val display = if (packageName.isNullOrEmpty()) name else "$packageName.$name"
        return PsiFlowNodes.fromElement(
            project,
            type,
            LanguageKind.KOTLIN,
            NodeKind.MODEL,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun nodeForMember(member: PsiElement): FlowNode? = when (member) {
        is KtNamedFunction -> declarationNode(member)
        is KtConstructor<*> -> constructorMemberNode(member)
        else -> null
    }

    private fun constructorMemberNode(constructor: KtConstructor<*>): FlowNode {
        val owner = constructor.containingClassOrObject?.name ?: "<anonymous>"
        val parameters = constructor.valueParameters.joinToString(", ") { parameter ->
            val type = parameter.typeReference?.text
            if (type != null) "${parameter.name ?: "_"}: $type" else parameter.name ?: "_"
        }
        return PsiFlowNodes.fromElement(
            project,
            constructor,
            LanguageKind.KOTLIN,
            NodeKind.METHOD,
            ResolutionStatus.RESOLVED,
            "$owner($parameters)"
        )
    }

    private fun javaMethodNode(method: PsiMethod): FlowNode {
        val parameters = method.parameterList.parameters.joinToString(", ") { parameter ->
            listOfNotNull(parameter.type.presentableText, parameter.name).joinToString(" ")
        }
        val owner = method.containingClass?.qualifiedName ?: method.containingClass?.name
        val display = if (owner != null) "$owner.${method.name}($parameters)" else "${method.name}($parameters)"
        return PsiFlowNodes.fromElement(
            project,
            method,
            LanguageKind.JAVA,
            NodeKind.METHOD,
            ResolutionStatus.RESOLVED,
            display
        )
    }

    private fun unresolvedNode(site: PsiElement): FlowNode {
        val callee = when (site) {
            is KtCallExpression -> site.calleeExpression?.text
            is KtCallableReferenceExpression -> "::" + site.callableReference.text
            is KtBinaryExpression -> site.operationReference.text
            else -> null
        }?.take(MAX_UNRESOLVED_LENGTH) ?: "<call>"
        return PsiFlowNodes.fromElement(
            project,
            site,
            LanguageKind.KOTLIN,
            NodeKind.UNKNOWN,
            ResolutionStatus.UNRESOLVED,
            "$callee(...)"
        )
    }

    private fun declarationDisplayName(function: KtNamedFunction): String {
        val name = function.name ?: "<anonymous>"
        val parameterText = function.valueParameters.joinToString(", ") { parameter ->
            val type = parameter.typeReference?.text
            if (type != null) "${parameter.name ?: "_"}: $type" else parameter.name ?: "_"
        }
        val receiver = function.receiverTypeReference?.text
        val owner = function.containingClassOrObject?.name
        val head = when {
            receiver != null -> "$receiver.$name"
            owner != null -> "$owner.$name"
            else -> name
        }
        val returnType = function.typeReference?.text?.let { ": $it" }.orEmpty()
        return "$head($parameterText)$returnType"
    }

    // -------------------------------------------------------------------------------------
    // Platform helpers
    // -------------------------------------------------------------------------------------

    private fun memberDeclarations(type: KtClassOrObject): List<PsiElement> {
        val functions = type.declarations.filterIsInstance<KtNamedFunction>()
        val constructors = listOfNotNull(type.primaryConstructor) + type.secondaryConstructors
        return (functions + constructors).sortedBy { it.textOffset }
    }

    /** Standard-library/project-external declarations are never expanded as project flow steps. */
    private fun isLibraryDeclaration(declaration: PsiElement): Boolean = isLibraryElement(declaration)

    private fun isLibraryElement(element: PsiElement): Boolean {
        return try {
            val virtualFile = element.containingFile?.virtualFile ?: return false
            if (!virtualFile.isInLocalFileSystem) return false
            !ProjectFileIndex.getInstance(project).isInContent(virtualFile)
        } catch (t: Throwable) {
            false
        }
    }

    /** Unresolved operator/intrinsic calls are environment noise, not project flow steps. */
    private fun isUnresolvedNoise(site: PsiElement): Boolean {
        val callee = when (site) {
            is KtCallExpression -> site.calleeExpression
            is KtCallableReferenceExpression -> site.callableReference
            is KtBinaryExpression -> site.operationReference
            else -> null
        } ?: return true
        val text = callee.text
        if (text.isEmpty()) return true
        val first = text.first()
        if (!first.isLetter() && first != '_' && first != '`') return true
        return text in KOTLIN_INTRINSICS
    }

    private inline fun <reified T : PsiElement> PsiElement.parentOfTypeStrict(): T? =
        PsiTreeUtil.getParentOfType(this, T::class.java, false)

    private fun PsiElement.propertyLambdaContaining(): KtProperty? {
        var parent: PsiElement? = parent
        while (parent != null) {
            if (parent is KtProperty) {
                val initializer = parent.initializer
                if (initializer != null && initializer is org.jetbrains.kotlin.psi.KtLambdaExpression) return parent
                return null
            }
            if (parent is KtNamedFunction || parent is KtClassOrObject) return null
            parent = parent.parent
        }
        return null
    }

    companion object {
        internal const val MAX_NODES = 300
        internal const val MAX_MODELS = 40
        private const val MAX_UNRESOLVED_LENGTH = 80
        private const val CANDIDATE_SUFFIX = " [candidate implementation]"

        private const val NO_SCOPE_MESSAGE =
            "Please place the caret inside a Kotlin function, constructor, property lambda or class."

        internal const val KOTLIN_WARNING =
            "⚠ Kotlin extraction uses Kotlin PSI with the Kotlin plugin's semantic reference resolution " +
                "(K1/K2). Standard-library declarations are not expanded; interface implementations are " +
                "reported as candidates, not guaranteed runtime targets; unresolved calls stay unresolved."

        /** Common operators/infix-looking tokens that only become calls after resolution. */
        private val INFIX_LIKE = setOf(
            "to", "until", "downTo", "step", "and", "or", "xor", "shl", "shr", "ushr", "inv",
            "plus", "minus", "times", "div", "rem", "mod", "rangeTo", "contains", "in", "get", "set"
        )

        private val KOTLIN_INTRINSICS = setOf(
            "println", "print", "require", "requireNotNull", "check", "checkNotNull", "error", "TODO",
            "listOf", "setOf", "mapOf", "arrayOf", "mutableListOf", "mutableMapOf", "lazy",
            "sequenceOf", "emptyList", "emptyMap", "emptySet", "runCatching", "repeat",
            // Collection/scope extensions: stdlib declarations, never project flow steps.
            "map", "flatMap", "filter", "filterNot", "forEach", "forEachIndexed", "fold", "reduce",
            "let", "also", "apply", "run", "with", "takeIf", "takeUnless", "copy", "maxOf", "minOf",
            "toList", "toSet", "toMap", "joinToString", "sortedBy", "sortedWith", "associateBy",
            "any", "all", "none", "count", "first", "firstOrNull", "last", "lastOrNull", "getOrNull"
        )
    }
}
