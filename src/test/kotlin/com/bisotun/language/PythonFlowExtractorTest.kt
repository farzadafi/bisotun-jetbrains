package com.bisotun.language

import com.bisotun.domain.NodeKind
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the tokenizer strategy that backs Python extraction in editions where no
 * Python PSI is available.
 *
 * They cover the two decisions the extractor cannot delegate to PSI there: which scope owns the
 * caret and which calls belong to a scope, plus the cross-file import matching done by
 * [PythonCallResolver]. Everything asserted here is pure text parsing and the [PythonAnalysisContext]
 * disk reader, so no platform fixture is required.
 */
class PythonFlowExtractorTest {

    @Test
    fun `discovers module function and class method in source order`() {
        val outline = PythonFileOutline.parse(SERVICE_SOURCE)

        assertEquals(listOf("helper", "process"), outline.functions.map { it.name })
        assertEquals(listOf("PaymentService"), outline.types.map { it.name })
    }

    @Test
    fun `marks class methods and selects the innermost function at the caret`() {
        val outline = PythonFileOutline.parse(SERVICE_SOURCE)

        val method = outline.functionContaining(SERVICE_SOURCE.indexOf("payment.amount"))
        assertNotNull(method)
        assertEquals("process", method.name)
        assertEquals(NodeKind.METHOD, method.kind)

        val caretInClassBody = SERVICE_SOURCE.indexOf("def process")
        assertEquals("process", outline.functionContaining(caretInClassBody)?.name)
    }

    @Test
    fun `module scope lists its own calls only`() {
        val outline = PythonFileOutline.parse(MODULE_SOURCE)

        val moduleCalls = outline.callSitesIn(0, MODULE_SOURCE.length).mapRaw()

        assertEquals(listOf("start_billing"), moduleCalls)
    }

    @Test
    fun `function scope still sees calls of nested functions`() {
        val outline = PythonFileOutline.parse(MODULE_SOURCE)

        val allCalls = outline
            .callSitesIn(0, MODULE_SOURCE.length, skipNestedFunctions = false)
            .map { it.name }

        assertTrue("start_billing" in allCalls)
        assertTrue("helper" in allCalls)
    }

    @Test
    fun `function scope collapses member call chains to last segment`() {
        val outline = PythonFileOutline.parse(SERVICE_SOURCE)
        val process = outline.functionNamed("process") ?: error("no def process")

        val calls = outline.callSitesIn(process.startOffset, process.endOffset)

        assertEquals(1, calls.size)
        val site = calls.single()
        assertEquals("helper", site.name)
        assertEquals("self", site.qualifier)
        assertEquals("self", site.rootIdentifier)
        assertEquals("self.helper", site.rawText)
    }

    @Test
    fun `same file plain call resolves to the local function`() {
        val outline = PythonFileOutline.parse(MODULE_SOURCE)
        val source = sourceFor(outline, MODULE_SOURCE)
        val callInsideRun = MODULE_SOURCE.indexOf("helper()")

        val resolved = resolver().resolve(
            source,
            PythonCallSite("helper", null, "helper", callInsideRun, "helper()")
        ) { _, _ -> null }

        assertNotNull(resolved)
        assertEquals("helper", resolved.definition.name)
        assertEquals(source, resolved.source)
    }

    @Test
    fun `self method call resolves to the class method in the same file`() {
        val outline = PythonFileOutline.parse(SERVICE_SOURCE)
        val source = sourceFor(outline, SERVICE_SOURCE)
        val process = outline.functionNamed("process") ?: error("no process")

        val resolved = resolver().resolve(
            source,
            PythonCallSite("helper", "self", "self", process.startOffset, "self.helper()")
        ) { _, _ -> null }

        assertNotNull(resolved)
        assertEquals("helper", resolved.definition.name)
        assertEquals(NodeKind.METHOD, resolved.definition.kind)
    }

    @Test
    fun `from relative import resolves a method in another file`() {
        val dir = createTempDirectory("python-flow-test").toFile()
        try {
            val services = File(dir, "services.py")
            services.writeText(SERVICE_SOURCE)
            val runner = File(dir, "runner.py")
            runner.writeText(
                """
                from .services import PaymentService

                def run():
                    svc = PaymentService()
                    svc.helper()
                """.trimIndent()
            )
            val context = PythonAnalysisContext(dir.absolutePath)
            val source = context.sourceForPath(runner.absolutePath, LanguageKind.PYTHON)
                ?: error("no source for runner")

            val site = PythonCallSite("helper", "svc", "svc", 0, "svc.helper()")
            val resolved = resolver().resolve(source, site) { from, specifier ->
                context.sourceForPath(
                    context.resolveSpecifier(from.filePath, specifier) ?: return@resolve null,
                    from.languageKind
                )
            }

            assertNotNull(resolved)
            assertEquals("helper", resolved.definition.name)
            assertEquals(services.absolutePath, resolved.source.filePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unresolvable member call stays unresolved instead of inventing a target`() {
        val outline = PythonFileOutline.parse(SERVICE_SOURCE)
        val source = sourceFor(outline, SERVICE_SOURCE)
        val process = outline.functionNamed("process") ?: error("no process")

        // `external_client` has no import binding and no local type, so this must be null.
        val resolved = resolver().resolve(
            source,
            PythonCallSite("fetch", "external_client", "external_client", process.startOffset, "external_client.fetch()")
        ) { _, _ -> null }

        assertNull(resolved)
    }

    @Test
    fun `resolveSpecifier supports py init and native python relative forms`() {
        val dir = createTempDirectory("python-flow-spec").toFile()
        try {
            File(dir, "pkg").mkdirs()
            File(dir, "pkg/__init__.py").writeText("")
            val mod = File(dir, "mod.py")
            mod.writeText("")
            val context = PythonAnalysisContext(dir.absolutePath)

            val parent = File(dir, "main.py")
            assertEquals(
                mod.absolutePath,
                context.resolveSpecifier(parent.absolutePath, "./mod")
            )
            assertEquals(
                File(dir, "mod.py").absolutePath,
                context.resolveSpecifier(parent.absolutePath, ".mod")
            )
            assertEquals(
                File(dir, "pkg/__init__.py").absolutePath,
                context.resolveSpecifier(parent.absolutePath, ".pkg")
            )
            assertEquals(
                File(dir, "pkg/__init__.py").absolutePath,
                context.resolveSpecifier(parent.absolutePath, "./pkg")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `absolute local import resolves like its relative counterpart`() {
        val dir = createTempDirectory("python-flow-user-service").toFile()
        try {
            val service = File(dir, "service.py")
            service.writeText(SERVICE_SOURCE)
            val context = PythonAnalysisContext(dir.absolutePath)

            val runner = File(dir, "runner.py")
            val relative = context.resolveSpecifier(runner.absolutePath, ".service")
            val absolute = context.resolveSpecifier(runner.absolutePath, "service")

            assertEquals(service.absolutePath, relative)
            assertEquals(service.absolutePath, absolute)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `absolute import resolves against basePath when not next to the importer`() {
        val dir = createTempDirectory("python-flow-base").toFile()
        try {
            File(dir, "app").mkdirs()
            File(dir, "app/__init__.py").writeText("")
            val service = File(dir, "app/service.py")
            service.writeText(SERVICE_SOURCE)
            val nested = File(dir, "core")
            nested.mkdirs()
            val runner = File(nested, "main.py")

            val context = PythonAnalysisContext(dir.absolutePath)
            assertEquals(
                service.absolutePath,
                context.resolveSpecifier(runner.absolutePath, "app.service")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stdlib absolute imports stay unresolved`() {
        val dir = createTempDirectory("python-flow-stdlib").toFile()
        try {
            val runner = File(dir, "runner.py")
            val context = PythonAnalysisContext(dir.absolutePath)

            assertNull(context.resolveSpecifier(runner.absolutePath, "json"))
            assertNull(context.resolveSpecifier(runner.absolutePath, "os.path"))
            assertNull(context.resolveSpecifier(runner.absolutePath, "no_such_project_module"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `absolute from import resolves a method in another file`() {
        val dir = createTempDirectory("python-flow-absolute").toFile()
        try {
            val services = File(dir, "service.py")
            services.writeText(
                """
                class UserService:
                    def helper(self):
                        return 1
                """.trimIndent()
            )
            val runner = File(dir, "runner.py")
            runner.writeText(
                """
                from service import UserService

                def run():
                    user_service = UserService()
                    user_service.helper()
                """.trimIndent()
            )
            val context = PythonAnalysisContext(dir.absolutePath)
            val source = context.sourceForPath(runner.absolutePath, LanguageKind.PYTHON)
                ?: error("no source for runner")

            val site = PythonCallSite("helper", "user_service", "user_service", 0, "user_service.helper()")
            val resolved = resolver().resolve(source, site) { from, specifier ->
                context.sourceForPath(
                    context.resolveSpecifier(from.filePath, specifier) ?: return@resolve null,
                    from.languageKind
                )
            }

            assertNotNull(resolved)
            assertEquals("helper", resolved.definition.name)
            assertEquals(services.absolutePath, resolved.source.filePath)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `language kind falls back to the file extension for unparsed files`() {
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("py"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("pyw"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("pyi"))
    }

    private fun sourceFor(outline: PythonFileOutline, text: String): PythonFlowSource =
        PythonFlowSource("/fixture/app.py", text, outline, LanguageKind.PYTHON)

    private fun resolver(): PythonCallResolver = PythonCallResolver()

    private fun List<PythonCallSite>.mapRaw(): List<String> = map { it.rawText }

    private companion object {
        val SERVICE_SOURCE = """
            class PaymentService:
                def helper(self):
                    return 1

                def process(self):
                    total = payment.amount
                    value = self.helper()
                    return total + value
        """.trimIndent()

        val MODULE_SOURCE = """
            start_billing()

            def run():
                return helper()

            def helper():
                return 1
        """.trimIndent()
    }
}

