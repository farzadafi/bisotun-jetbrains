package com.bisotun.language

import com.bisotun.MarkdownFormatter
import com.bisotun.domain.FlowNode
import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import com.goide.psi.GoFile
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Go tests run against the real JetBrains Go plugin PSI (the plugin is a test dependency, and the
 * Gradle IntelliJ Platform plugin loads it into the test sandbox), never a mock or a tokenizer.
 */
class GoFlowExtractorTest : BasePlatformTestCase() {

    private lateinit var files: MutableMap<String, PsiFile>

    override fun setUp() {
        super.setUp()
        files = LinkedHashMap()
        addFile("go.mod", "module example.com/bisotun\n\ngo 1.21\n")
        addFile("service/order_service.go", resource("service/order_service.go"))
        addFile("service/order_repository.go", resource("service/order_repository.go"))
        addFile("service/workflow.go", resource("service/workflow.go"))
        addFile("service/generated.go", resource("service/generated.go"))
        addFile("service/malformed.go", resource("service/malformed.go"))
        addFile("notify/notifier.go", resource("notify/notifier.go"))
        addFile("cmd/main.go", resource("cmd/main.go"))
    }

    // ----------------------------------------------------------------------------------
    // Language detection
    // ----------------------------------------------------------------------------------

    fun testGoDetectionUsesExtensionAndLanguageId() {
        assertEquals(LanguageKind.GO, LanguageKind.fromExtension("go"))
        assertEquals(LanguageKind.GO, LanguageKind.fromExtension("GO"))
        assertEquals(LanguageKind.GO, LanguageKind.fromLanguageId("go"))
        assertEquals(LanguageKind.GO, LanguageKind.fromLanguageId("Go"))
        assertEquals("go", LanguageKind.GO.codeFence)
    }

    fun testGoFileIsRealGoPsi() {
        val file = files.getValue("service/order_service.go")
        assertTruth(file is GoFile)
        val goFile = file as GoFile
        assertEquals("service", goFile.packageName)
        assertEquals(
            listOf("fmt", "example.com/bisotun/notify"),
            goFile.imports.map { it.path }
        )
    }

    fun testOtherLanguagesKeepTheirDetection() {
        assertEquals(LanguageKind.JAVA, LanguageKind.fromExtension("java"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("py"))
        assertEquals(LanguageKind.CPP, LanguageKind.fromExtension("hpp"))
        assertEquals(LanguageKind.TS, LanguageKind.fromExtension("ts"))
        assertEquals(LanguageKind.UNKNOWN, LanguageKind.fromExtension("rs"))
    }

    // ----------------------------------------------------------------------------------
    // Declarations through Go PSI
    // ----------------------------------------------------------------------------------

    fun testStructsInterfacesEmbeddingAndGenericsAreRealDeclarations() {
        val result = extract("service/order_service.go", "package service", maxDepth = 1, includeModels = true)
        val modelNames = result.models.map { it.displayName }

        assertTruth("Order" in modelNames, modelNames.toString())
        assertTruth("Customer" in modelNames, modelNames.toString())
        assertTruth("CreateOrderRequest" in modelNames, modelNames.toString())
        assertTruth("OrderItem" in modelNames, modelNames.toString())
        assertTruth("Repository" in modelNames, modelNames.toString())
        assertTruth("Notifier" in modelNames, modelNames.toString())

        val repository = result.models.first { it.displayName == "Repository" }
        assertTruth(repository.code.contains("interface"), repository.code)
        assertTruth(repository.code.contains("Save(T) error"), repository.code)
        assertTruth(repository.code.contains("[T any]"), repository.code)

        val order = result.models.first { it.displayName == "Order" }
        assertTruth(order.code.contains("Customer Customer"), order.code)
        assertTruth(order.range.startLine >= 1)
        assertEquals(ResolutionStatus.RESOLVED, order.resolutionStatus)
    }

    fun testFunctionsMethodsReceiversAndMultipleReturns() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 1)

        val root = result.root
        assertEquals(NodeKind.METHOD, root.kind)
        assertEquals("OrderService.Create(request CreateOrderRequest) (Order, error)", root.displayName)

        val buildOrder = result.nodes.firstOrNull { it.displayName.startsWith("OrderService.buildOrder(") }
            ?: error("buildOrder must resolve: ${result.names()}")
        assertEquals(NodeKind.METHOD, buildOrder.kind)
        assertEquals("OrderService.buildOrder(request CreateOrderRequest) (Order, error)", buildOrder.displayName)
        assertTruth(buildOrder.filePath.endsWith("order_service.go"), buildOrder.filePath)

        val constructor = extract("service/order_service.go", "func NewOrderService", maxDepth = 1)
        assertEquals(NodeKind.FUNCTION, constructor.root.kind)
        assertEquals("NewOrderService(repo Repository[Order], notifier Notifier) *OrderService", constructor.root.displayName)
    }

    fun testValueReceiverMethodIsExtracted() {
        val result = extract("service/order_service.go", "func (s OrderService) describe", maxDepth = 1)
        assertEquals("OrderService.describe() string", result.root.displayName)
        assertEquals(NodeKind.METHOD, result.root.kind)
    }

    fun testTypeScopeListsMethods() {
        val result = extract("service/order_service.go", "repo     Repository", maxDepth = 1)

        assertEquals("service.OrderService", result.root.displayName)
        assertEquals(NodeKind.MODEL, result.root.kind)
        assertTruth(
            result.nodes.any { it.displayName.startsWith("OrderService.Create(") },
            result.names().toString()
        )
        assertTruth(
            result.nodes.any { it.displayName.startsWith("OrderService.describe(") },
            result.names().toString()
        )
    }

    fun testLocalVariablesAndShortDeclarationsCarryInferredTypes() {
        val result = extract("service/order_service.go", "func (s *OrderService) buildOrder", maxDepth = 1)
        val locals = result.nodes.filter { it.displayName.startsWith("total") || it.displayName.startsWith("id ") }

        val total = locals.firstOrNull { it.displayName.startsWith("total ") }
            ?: error("var total must be extracted: ${result.names()}")
        assertTruth(total.displayName.contains("float64"), total.displayName)

        val id = locals.firstOrNull { it.displayName.startsWith("id ") }
            ?: error("short declaration id must be extracted: ${result.names()}")
        assertTruth(id.displayName == "id string", id.displayName)
    }

    // ----------------------------------------------------------------------------------
    // Flow and depth
    // ----------------------------------------------------------------------------------

    fun testCallChainRespectsMaxDepth() {
        val shallow = extract("service/order_service.go", "s.repo.Save", maxDepth = 1)
        assertTruth(shallow.names().any { it.startsWith("OrderService.buildOrder(") }, shallow.names().toString())
        assertFalsity(shallow.names().any { it.startsWith("normalize(") }, shallow.names().toString())

        val deep = extract("service/order_service.go", "s.repo.Save", maxDepth = 2)
        assertTruth(deep.names().any { it.startsWith("normalize(") }, deep.names().toString())
        assertTruth(deep.names().any { it.startsWith("newID(") }, deep.names().toString())
    }

    fun testCrossFileSamePackageCallResolves() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 2)
        val newId = result.nodes.firstOrNull { it.displayName.startsWith("newID(") }
            ?: error("cross-file call newID must resolve: ${result.names()}")
        assertTruth(newId.filePath.endsWith("order_repository.go"), newId.filePath)
        assertEquals(ResolutionStatus.RESOLVED, newId.resolutionStatus)
    }

    fun testInterfaceCallsStayAtTheInterfaceDeclaration() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 4)
        val save = result.nodes.firstOrNull { it.displayName.startsWith("Repository.Save(") }
            ?: error("interface method Save must be reported: ${result.names()}")
        assertEquals(NodeKind.METHOD, save.kind)
        assertEquals(ResolutionStatus.RESOLVED, save.resolutionStatus)
        assertFalsity(
            result.names().any { it.startsWith("OrderRepository.Save(") },
            result.names().toString()
        )
    }

    fun testUnknownReceiversAreNeverLinkedByName() {
        val result = extract("service/order_service.go", "client.Save", maxDepth = 1)
        val unresolved = result.nodes.firstOrNull { it.displayName.startsWith("client.Save(") }
            ?: error("client.Save must be reported: ${result.names()}")
        assertEquals(ResolutionStatus.UNRESOLVED, unresolved.resolutionStatus)
        assertFalsity(result.names().any { it.startsWith("Repository.Save(") }, result.names().toString())

        val external = extract("service/order_service.go", "external.Send", maxDepth = 1)
        assertEquals(
            ResolutionStatus.UNRESOLVED,
            external.nodes.first { it.displayName.startsWith("external.Send(") }.resolutionStatus
        )
    }

    fun testBuiltinsAndStandardLibraryCallsAreFiltered() {
        val result = extract("cmd/main.go", "println(order.ID)", maxDepth = 2)
        assertFalsity(result.names().any { it.startsWith("println(") }, result.names().toString())
        assertFalsity(result.names().any { it.contains("Sprintf(") }, result.names().toString())

        val notifier = extract("notify/notifier.go", "func (n *EmailNotifier) Notify", maxDepth = 1)
        assertFalsity(notifier.names().any { it.contains("Println(") }, notifier.names().toString())
    }

    fun testGeneratedAndMalformedFilesAreHandled() {
        val generated = extract("service/generated.go", "func GeneratedHelper", maxDepth = 1)
        assertEquals("GeneratedHelper()", generated.root.displayName)

        val malformed = extract("service/malformed.go", "type Broken", maxDepth = 4, includeModels = true)
        assertTruth(malformed.root.displayName.startsWith("package broken"), malformed.root.displayName)
        assertTruth(malformed.models.any { it.displayName == "Broken" }, malformed.models.map { it.displayName }.toString())
    }

    // ----------------------------------------------------------------------------------
    // Control flow, goroutines, closures, generics
    // ----------------------------------------------------------------------------------

    fun testControlFlowStatementsAreExtracted() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 1)
        val statements = result.nodes.map { it.displayName }

        assertTruth(statements.any { it.startsWith("if err != nil") }, statements.toString())
        assertTruth(statements.any { it.startsWith("return") }, statements.toString())

        val buildOrder = extract("service/order_service.go", "func (s *OrderService) buildOrder", maxDepth = 1)
        val buildStatements = buildOrder.nodes.map { it.displayName }
        assertTruth(buildStatements.any { it.contains("range request.Items") }, buildStatements.toString())
        assertTruth(buildStatements.any { it.startsWith("switch ") }, buildStatements.toString())
    }

    fun testGoroutinesDeferSelectAndTypeSwitchAreExtracted() {
        val result = extract("service/workflow.go", "results <- handler", maxDepth = 1)
        val statements = result.nodes.map { it.displayName }

        assertTruth(statements.any { it.startsWith("go func(") }, statements.toString())
        assertTruth(statements.any { it.startsWith("defer ") }, statements.toString())
        assertTruth(statements.any { it.startsWith("select") }, statements.toString())
        assertTruth(statements.any { it.startsWith("switch ") }, statements.toString())
    }

    fun testGenericsArePreservedOnDeclarations() {
        val result = extract("service/workflow.go", "type Job", maxDepth = 1, includeModels = true)
        val job = result.models.firstOrNull { it.displayName == "Job" }
            ?: error("Job must be a model: ${result.models.map { it.displayName }}")
        assertTruth(job.code.contains("[T any]"), job.code)

        val runJobs = extract("service/workflow.go", "results <- handler", maxDepth = 1)
        assertTruth(
            runJobs.root.displayName.startsWith("RunJobs(jobs []Job[T], handler Handler[T])"),
            runJobs.root.displayName
        )
    }

    // ----------------------------------------------------------------------------------
    // Models, source ranges, rendering, cancellation
    // ----------------------------------------------------------------------------------

    fun testModelsFollowFieldsAndEmbeddedTypes() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 2, includeModels = true)
        val modelNames = result.models.map { it.displayName }

        assertTruth("CreateOrderRequest" in modelNames, modelNames.toString())
        assertTruth("Order" in modelNames, modelNames.toString())
        assertTruth("Customer" in modelNames, modelNames.toString())
        assertTruth("OrderItem" in modelNames, modelNames.toString())
        assertTruth(result.models.all { it.kind == NodeKind.MODEL })
        assertTruth(result.models.all { it.resolutionStatus == ResolutionStatus.RESOLVED })
        assertTruth(result.models.all { it.code.isNotBlank() })

        val withoutModels = extract("service/order_service.go", "s.repo.Save", maxDepth = 2, includeModels = false)
        assertTruth(withoutModels.models.isEmpty())
    }

    fun testEveryNodeMapsToRealSource() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 2, includeModels = true)
        val sources = files.values.filterIsInstance<GoFile>().associateBy { it.name }

        for (node in result.allNodes + result.models) {
            val file = sources[node.filePath.substringAfterLast('/')] ?: continue
            assertTruth(node.range.startLine >= 1, node.displayName)
            assertTruth(node.range.endLine >= node.range.startLine, node.displayName)
            assertTruth(node.code.isNotBlank(), node.displayName)
            assertTruth(file.text.contains(node.code.lines().first().trim()), node.displayName)
        }
    }

    fun testMarkdownRenderingStaysCompatible() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 2, includeModels = true)
        val markdown = MarkdownFormatter().format(result, 2, true)

        assertTruth(markdown.startsWith("# Flow Extracted by Bisotun"))
        assertTruth(markdown.contains("**Configuration:** maxDepth=2, includeModels=true"))
        assertTruth(markdown.contains("## Execution Flow Summary"))
        assertTruth(markdown.contains("## Source Code & Implementations"))
        assertTruth(markdown.contains("## Related Data Models & Entities"))
        assertTruth(markdown.contains("```go"))
        assertTruth(markdown.contains("OrderService.Create(request CreateOrderRequest)"))
    }

    fun testWarningDocumentsPsiSemantics() {
        val result = extract("service/order_service.go", "s.repo.Save", maxDepth = 1)
        assertTruth(result.warnings.single().contains("Go PSI"), result.warnings.toString())
        assertTruth(result.warnings.single().contains("interface"), result.warnings.toString())
    }

    fun testCancellationIsPropagated() {
        val indicator: ProgressIndicator = ProgressIndicatorBase().apply { cancel() }
        var canceled = false
        try {
            ProgressManager.getInstance().runProcess({
                extract("service/order_service.go", "s.repo.Save", maxDepth = 1)
            }, indicator)
        } catch (_: ProcessCanceledException) {
            canceled = true
        }
        assertTruth(canceled, "PSI access must honour a canceled progress indicator")
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    private fun assertTruth(value: Boolean, message: String = "expected true") {
        if (!value) fail(message)
    }

    private fun assertFalsity(value: Boolean, message: String = "expected false") {
        if (value) fail(message)
    }

    private fun addFile(path: String, text: String): PsiFile? =
        myFixture.addFileToProject(path, text).also { files[path] = it }

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/go/$path")) {
            "fixtures/go/$path must exist on the test classpath"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun extract(
        path: String,
        caretSnippet: String,
        maxDepth: Int = 1,
        includeModels: Boolean = false
    ): FlowResult {
        val file = files.getValue(path)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val text = file.text
        val caret = text.indexOf(caretSnippet)
        assertTruth(caret >= 0, "caret snippet `$caretSnippet` not found in $path")
        myFixture.editor.caretModel.moveToOffset(caret)

        val outcome = GoFlowExtractor(project).extractFlow(file, myFixture.editor, maxDepth, includeModels)
        assertTruth(
            outcome.isSuccess,
            "extraction failed: ${outcome.message}"
        )
        return checkNotNull(outcome.result)
    }

    private fun FlowResult.names(): List<String> = allNodes.map { it.displayName }
}
