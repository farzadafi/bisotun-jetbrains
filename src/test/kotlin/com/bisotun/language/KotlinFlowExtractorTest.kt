package com.bisotun.language

import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.bisotun.kotlin.KotlinFlowExtractor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtFile

/**
 * Kotlin regression tests against real Kotlin PSI (the Kotlin plugin is bundled with the build IDE
 * and is a test dependency). Nothing here is tokenized or matched by name: declarations and calls
 * come from Kotlin PSI and are resolved by the Kotlin plugin's own reference resolution.
 */
class KotlinFlowExtractorTest : BasePlatformTestCase() {

    private lateinit var files: MutableMap<String, PsiFile>

    override fun setUp() {
        super.setUp()
        files = LinkedHashMap()
        addFile("Models.kt", resource("Models.kt"))
        addFile("Extensions.kt", resource("Extensions.kt"))
        addFile("Repositories.kt", resource("Repositories.kt"))
        addFile("OrderService.kt", resource("OrderService.kt"))
    }

    // ----------------------------------------------------------------------------------
    // Language detection and PSI
    // ----------------------------------------------------------------------------------

    fun testKotlinDetectionUsesLanguageIdAndExtension() {
        assertEquals(LanguageKind.KOTLIN, LanguageKind.fromExtension("kt"))
        assertEquals(LanguageKind.KOTLIN, LanguageKind.fromExtension("kts"))
        assertEquals(LanguageKind.KOTLIN, LanguageKind.fromLanguageId("kotlin"))
        assertEquals("kotlin", LanguageKind.KOTLIN.codeFence)
        assertEquals("Kotlin", LanguageKind.KOTLIN.displayName)
    }

    fun testOtherLanguagesKeepTheirDetection() {
        assertEquals(LanguageKind.JAVA, LanguageKind.fromExtension("java"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("py"))
        assertEquals(LanguageKind.CPP, LanguageKind.fromExtension("hpp"))
        assertEquals(LanguageKind.GO, LanguageKind.fromExtension("go"))
        assertEquals(LanguageKind.TS, LanguageKind.fromExtension("ts"))
        assertEquals(LanguageKind.UNKNOWN, LanguageKind.fromExtension("rs"))
    }

    fun testKotlinFileIsRealKotlinPsi() {
        val file = files.getValue("OrderService.kt")
        assertTruth(file is KtFile)
        assertEquals("com.example.service", (file as KtFile).packageFqName.asString())
    }

    // ----------------------------------------------------------------------------------
    // Root selection and depth
    // ----------------------------------------------------------------------------------

    fun testRootIsTheInnermostFunctionAtTheCaret() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 1)

        assertEquals("OrderService.create(request: OrderRequest): Receipt", result.root.displayName)
        assertEquals(NodeKind.METHOD, result.root.kind)
        assertEquals(ResolutionStatus.RESOLVED, result.root.resolutionStatus)
        assertTruth(result.root.range.startLine >= 1)
        assertTruth(result.root.code.contains("fun create(request: OrderRequest)"))
    }

    fun testShallowFlowExpandsOnlyDirectCalls() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 1)
        val names = result.names()

        assertTruth(names.any { it == "OrderService.buildOrder(request: OrderRequest): Order" }, names.toString())
        assertTruth(names.any { it.startsWith("OrderRepository.save(order: Order)") }, names.toString())
        assertTruth(names.any { it.startsWith("Notifier.notify(order: Order)") }, names.toString())
        assertFalsity(
            names.any { it == "OrderService.addItem(order: Order, item: OrderItem): Order" },
            "addItem is only reachable through buildOrder, so depth=1 must not report it: $names"
        )
    }

    fun testDeepFlowReachesCrossFileAndPrivateHelpers() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 4)
        val names = result.names()

        assertTruth(names.any { it == "OrderService.addItem(order: Order, item: OrderItem): Order" }, names.toString())
        assertTruth(names.any { it == "OrderService.normalize(item: OrderItem): OrderItem" }, names.toString())
        assertTruth(names.any { it.startsWith("Order.withItem(item: OrderItem): Order") }, names.toString())
        assertTruth(names.any { it == "Notifier.send(message: String)" }, names.toString())

        val crossFile = result.nodes.firstOrNull { it.displayName.startsWith("Order.withItem(") }
            ?: error("withItem must resolve: $names")
        assertTruth(crossFile.filePath.endsWith("Extensions.kt"), crossFile.filePath)
        assertEquals(ResolutionStatus.RESOLVED, crossFile.resolutionStatus)
    }

    fun testMethodOnlyExtractsOnlyTheRoot() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 0)
        assertEquals(1, result.allNodes.size)
        assertTruth(result.models.isEmpty())
    }

    // ----------------------------------------------------------------------------------
    // Overloads, constructors, lambdas, callable references, extensions
    // ----------------------------------------------------------------------------------

    fun testOverloadsResolveBySemanticsNotName() {
        val result = extract("OrderService.kt", "overload(\"a\")", maxDepth = 1)
        val overloads = result.nodes.filter { it.displayName.startsWith("OrderService.overload(") }

        assertEquals(2, overloads.size)
        assertTruth(overloads.any { it.displayName == "OrderService.overload(value: String): String" })
        assertTruth(overloads.any { it.displayName == "OrderService.overload(value: Int): String" })
        assertEquals(
            overloads.size,
            overloads.map { it.id }.distinct().size
        )
    }

    fun testConstructorIsReportedAtItsDeclaration() {
        val result = extract("OrderService.kt", "val order = Order(request.customerId", maxDepth = 1)
        val constructor = result.nodes.firstOrNull { it.displayName == "Order(request.customerId, items)" }
            ?: error("constructor call must be reported: ${result.names()}")
        assertTruth(constructor.filePath.endsWith("Models.kt"), constructor.filePath)
        assertTruth(constructor.code.contains("data class Order("), constructor.code)
    }

    fun testLambdasStayInTheEnclosingFlow() {
        val result = extract("OrderService.kt", "val transform =", maxDepth = 1)
        val normalize = result.nodes.firstOrNull {
            it.displayName == "OrderService.normalize(item: OrderItem): OrderItem"
        } ?: error("calls inside a lambda body execute and must be reported: ${result.names()}")

        assertEquals(ResolutionStatus.RESOLVED, normalize.resolutionStatus)
        assertTruth(
            result.names().none { it.contains("map(") },
            "stdlib map must not be expanded as a project method: ${result.names()}"
        )
    }

    fun testCallableReferenceResolvesToItsDeclaration() {
        val result = extract("OrderService.kt", "::overload", maxDepth = 1)
        val reference = result.nodes.firstOrNull { it.displayName.startsWith("OrderService.overload(") }
            ?: error("callable reference must resolve: ${result.names()}")
        assertEquals(ResolutionStatus.RESOLVED, reference.resolutionStatus)
    }

    fun testExtensionFunctionsResolveAcrossFiles() {
        val result = extract("OrderService.kt", "updated.describe()", maxDepth = 1)
        val describe = result.nodes.firstOrNull { it.displayName.startsWith("Order.describe(") }
            ?: error("extension function must resolve: ${result.names()}")

        assertTruth(describe.filePath.endsWith("Extensions.kt"), describe.filePath)
        assertEquals(ResolutionStatus.RESOLVED, describe.resolutionStatus)
        assertTruth(describe.code.contains("fun Order.describe()"), describe.code)
    }

    fun testLocalFunctionBodyIsNotPartOfTheEnclosingFlow() {
        val result = extract("OrderService.kt", "return 0", maxDepth = 2)
        assertTruth(
            result.names().none { it == "OrderService.helper(value: Int): Int" },
            "calls inside a local function belong to that function, not the enclosing body: ${result.names()}"
        )
    }

    // ----------------------------------------------------------------------------------
    // Interface dispatch, cycles, stdlib noise
    // ----------------------------------------------------------------------------------

    fun testInterfaceCallStaysAtTheInterfaceAndListsCandidates() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 2)
        val interfaceNode = result.nodes.firstOrNull {
            it.displayName.startsWith("OrderRepository.save(")
        } ?: error("interface method must be reported: ${result.names()}")
        assertEquals(ResolutionStatus.RESOLVED, interfaceNode.resolutionStatus)

        val candidates = result.nodes.filter { it.displayName.endsWith(" [candidate implementation]") }
        assertTruth(candidates.isNotEmpty(), "implementations must be labelled candidates: ${result.names()}")
        assertTruth(
            candidates.any { it.displayName.startsWith("InMemoryOrderRepository.save(") },
            candidates.map { it.displayName }.toString()
        )
        assertTruth(
            candidates.any { it.displayName.startsWith("DatabaseOrderRepository.save(") },
            candidates.map { it.displayName }.toString()
        )
        assertTruth(
            result.warnings.any { it.contains("Candidate implementations") },
            result.warnings.toString()
        )
    }

    fun testStrictWiringOmitsInterfaceCandidates() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 2, strictWiring = true)
        assertTruth(result.names().any { it.startsWith("OrderRepository.save(") }, result.names().toString())
        assertTruth(
            result.nodes.none { it.displayName.endsWith(" [candidate implementation]") },
            "strict wiring must not claim implementations: ${result.names()}"
        )
        assertTruth(result.warnings.none { it.contains("Candidate implementations") })
    }

    fun testCyclesAreDeduplicated() {
        val result = extract("OrderService.kt", "pong(depth)", maxDepth = 4)
        val pings = result.allNodes.filter { it.displayName.startsWith("OrderService.ping(") }
        val pongs = result.allNodes.filter { it.displayName.startsWith("OrderService.pong(") }

        assertEquals(1, pings.size)
        assertEquals(1, pongs.size)
        assertTruth(pings.size == 1 && pongs.size == 1, result.names().toString())
    }

    fun testStandardLibraryCallsAreNotProjectFlowSteps() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 4, includeModels = true)
        val names = result.allNodes.map { it.displayName }

        assertTruth(names.none { it.contains("println") }, names.toString())
        assertTruth(names.none { it.contains("map(") }, names.toString())
        assertTruth(names.none { it.contains("maxOf(") }, names.toString())
        assertTruth(names.none { it.contains("copy(") }, names.toString())
    }

    // ----------------------------------------------------------------------------------
    // Models and declarations
    // ----------------------------------------------------------------------------------

    fun testModelsAreRealProjectTypesOnly() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 2, includeModels = true)
        val modelNames = result.models.map { it.displayName }

        assertTruth("com.example.models.OrderRequest" in modelNames, modelNames.toString())
        assertTruth("com.example.models.Receipt" in modelNames, modelNames.toString())
        assertTruth("com.example.models.Order" in modelNames, modelNames.toString())
        assertTruth("com.example.models.OrderItem" in modelNames, modelNames.toString())
        assertTruth(result.models.all { it.kind == NodeKind.MODEL })
        assertTruth(result.models.all { it.resolutionStatus == ResolutionStatus.RESOLVED })
        assertTruth(result.models.all { it.code.isNotBlank() })
        assertTruth(modelNames.none { it.startsWith("kotlin.") }, "stdlib types are not models: $modelNames")
    }

    fun testModelsAreAbsentWhenIncludeModelsIsFalse() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 2, includeModels = false)
        assertTruth(result.models.isEmpty())
    }

    fun testClassScopeListsConstructorsAndMethods() {
        val result = extract("OrderService.kt", "private val repository", maxDepth = 1)
        assertEquals("com.example.service.OrderService", result.root.displayName)
        assertEquals(NodeKind.MODEL, result.root.kind)
        assertTruth(result.names().any { it == "OrderService.create(request: OrderRequest): Receipt" })
        assertTruth(result.names().any { it.startsWith("OrderService.buildOrder(") })
        assertTruth(result.names().any { it.startsWith("OrderService(") }, result.names().toString())
    }

    fun testPropertyLambdaIsAValidRoot() {
        val result = extract("OrderService.kt", "val handler", maxDepth = 1)
        assertEquals("OrderService.handler()", result.root.displayName)
        assertTruth(
            result.names().any { it == "OrderService.helper(value: Int): Int" },
            result.names().toString()
        )
    }

    fun testEveryNodePointsAtRealSource() {
        val result = extract("OrderService.kt", "repository.save(order)", maxDepth = 4, includeModels = true)
        for (node in result.allNodes + result.models) {
            assertTruth(node.range.startLine >= 1, node.displayName)
            assertTruth(node.range.endLine >= node.range.startLine, node.displayName)
            assertTruth(node.code.isNotBlank(), node.displayName)
            assertTruth(node.filePath.endsWith(".kt"), node.filePath)
        }
    }

    fun testUnresolvedCallsAreHonest() {
        val text = """
            package com.example.synthetic

            fun start() {
                missingThing(1)
            }
        """.trimIndent()
        val file = addFile("Synthetic.kt", text)!!

        val result = extract(file, "missingThing")
        val unresolved = result.nodes.filter { it.displayName.startsWith("missingThing") }
        assertTruth(unresolved.isNotEmpty(), result.names().toString())
        assertEquals(ResolutionStatus.UNRESOLVED, unresolved.first().resolutionStatus)
        assertEquals(NodeKind.UNKNOWN, unresolved.first().kind)
        assertTruth(unresolved.first().range.startLine >= 1)
    }

    fun testMalformedKotlinDoesNotThrow() {
        val file = addFile("Broken.kt", "package broken\n\nfun broken( { if (")!!
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val outcome = KotlinFlowExtractor(project).extractFlow(file, myFixture.editor, 2, true)
        assertTruth(outcome.message != null || outcome.result != null)
    }

    fun testCancellationIsPropagated() {
        val file = files.getValue("OrderService.kt")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(file.text.indexOf("repository.save(order)"))

        var canceled = false
        try {
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess({
                KotlinFlowExtractor(project).extractFlow(file, myFixture.editor, 1, false)
            }, com.intellij.openapi.progress.util.ProgressIndicatorBase().apply { cancel() })
        } catch (_: ProcessCanceledException) {
            canceled = true
        }
        assertTruth(canceled, "PSI access must honour a canceled progress indicator")
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    private fun addFile(path: String, text: String): PsiFile? =
        myFixture.addFileToProject(path, text).also { files[path] = it }

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/kotlin/$path")) {
            "fixtures/kotlin/$path must exist on the test classpath"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun extract(
        path: String,
        caretSnippet: String,
        maxDepth: Int = 1,
        includeModels: Boolean = false,
        strictWiring: Boolean = false
    ): FlowResult = extract(files.getValue(path), caretSnippet, maxDepth, includeModels, strictWiring)

    private fun extract(
        file: PsiFile,
        caretSnippet: String,
        maxDepth: Int = 1,
        includeModels: Boolean = false,
        strictWiring: Boolean = false
    ): FlowResult {
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val caret = file.text.indexOf(caretSnippet)
        assertTruth(caret >= 0, "caret snippet `$caretSnippet` not found in ${file.name}")
        myFixture.editor.caretModel.moveToOffset(caret)

        val outcome = KotlinFlowExtractor(project, strictWiring)
            .extractFlow(file, myFixture.editor, maxDepth, includeModels)
        assertTruth(outcome.isSuccess, "extraction failed: ${outcome.message}")
        return checkNotNull(outcome.result)
    }

    private fun FlowResult.names(): List<String> = allNodes.map { it.displayName }
    private fun assertTruth(value: Boolean, message: String? = null) {
        if (!value) fail(message ?: "expected true")
    }

    private fun assertFalsity(value: Boolean, message: String? = null) {
        if (value) fail(message ?: "expected false")
    }

}
