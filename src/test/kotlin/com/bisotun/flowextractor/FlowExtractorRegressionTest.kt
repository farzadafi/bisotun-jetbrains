package com.bisotun.flowextractor

import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the headless JS/TS flow extractor (tokenizer tier).
 *
 * These run on a plain JVM - no IntelliJ fixture, no JavaScript plugin - because the tokenizer tier
 * is the exact strategy the plugin uses in IntelliJ IDEA Community. They exercise the same
 * guarantees the UI flow has to keep: real scopes only, real cross-file resolution, honest
 * unresolved nodes, depth limits, and models reported only when a type declaration is actually
 * parsed.
 */
class FlowExtractorRegressionTest {

    private val fixtureDir: File by lazy {
        val url = checkNotNull(javaClass.classLoader.getResource("fixtures/flow")) {
            "fixtures/flow must exist on the test classpath"
        }
        File(url.toURI()).canonicalFile
    }

    private fun filePath(name: String): String = File(fixtureDir, name).absolutePath

    private fun source(name: String): String = File(fixtureDir, name).readText()

    private fun extractor(): TextFlowExtractor = TextFlowExtractor(fixtureDir.absolutePath)

    // ----------------------------------------------------------------------------------
    // Fixture sanity
    // ----------------------------------------------------------------------------------

    @Test
    fun `fixture files are readable`() {
        assertTrue(source("sample.js").contains("function greet"))
        assertTrue(source("users.js").contains("export function fetchUser"))
        assertTrue(source("types.ts").contains("export interface UserProfile"))
        assertTrue(source("UserProfileCard.tsx").contains("React.FC"))
    }

    // ----------------------------------------------------------------------------------
    // Root selection and method-not-found honesty
    // ----------------------------------------------------------------------------------

    @Test
    fun `unknown function name yields an unresolved root and no fabricated nodes`() {
        val result = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "doesNotExist")

        assertEquals(ResolutionStatus.UNRESOLVED, result.root.resolutionStatus)
        assertEquals(NodeKind.UNKNOWN, result.root.kind)
        assertTrue(result.nodes.isEmpty(), "no declaration exists, so no nodes may be fabricated")
        assertTrue(result.models.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(result.warnings.any { it.contains("Nothing was fabricated") })
    }

    @Test
    fun `method only extracts just the root`() {
        val result = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", maxDepth = 0)

        assertEquals(1, result.allNodes.size)
        assertEquals("buildGreeting(userId)", result.root.displayName)
        assertEquals(ResolutionStatus.RESOLVED, result.root.resolutionStatus)
        assertTrue(result.root.code.startsWith("buildGreeting(userId)"), "code snippet starts at the function name")
        assertTrue(result.models.isEmpty(), "method only means no models")
    }

    // ----------------------------------------------------------------------------------
    // Depth limits
    // ----------------------------------------------------------------------------------

    @Test
    fun `deep flow respects maxDepth`() {
        val searchPath = filePath("search.js")
        val searchText = source("search.js")

        fun resolvedNames(result: com.bisotun.domain.FlowResult): List<String> =
            result.allNodes.filter { it.resolutionStatus == ResolutionStatus.RESOLVED }.map { it.displayName }

        val depth4 = extractor().extractFlow(searchPath, searchText, "search", maxDepth = 4, includeModels = false)
        assertEquals(
            listOf("search(query)", "apiFetch(query)", "processResults(result)", "normalize(items)", "format(item)"),
            resolvedNames(depth4)
        )

        val depth3 = extractor().extractFlow(searchPath, searchText, "search", maxDepth = 3, includeModels = false)
        assertEquals(
            listOf("search(query)", "apiFetch(query)", "processResults(result)", "normalize(items)", "format(item)"),
            resolvedNames(depth3)
        )

        val depth2 = extractor().extractFlow(searchPath, searchText, "search", maxDepth = 2, includeModels = false)
        assertEquals(
            listOf("search(query)", "apiFetch(query)", "processResults(result)", "normalize(items)"),
            resolvedNames(depth2)
        )
    }

    // ----------------------------------------------------------------------------------
    // Resolution honesty
    // ----------------------------------------------------------------------------------

    @Test
    fun `same-file calls resolve to the real declaration`() {
        val result = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", includeModels = false)

        val byName = result.allNodes.associateBy { it.displayName }
        assertNotNull(byName["greet(name)"], "greet must resolve to the same-file declaration")
        assertEquals(ResolutionStatus.RESOLVED, byName["greet(name)"]!!.resolutionStatus)
        assertTrue(byName["greet(name)"]!!.code.startsWith("greet(name)"), "same-file code snippet starts at the declaration name")
    }


    @Test
    fun `cross-file imports resolve to the imported declaration`() {
        val result = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", includeModels = false)

        val byName = result.allNodes.associateBy { it.displayName }
        assertNotNull(byName["fetchUser(id)"], "fetchUser must resolve across files")
        assertEquals(ResolutionStatus.RESOLVED, byName["fetchUser(id)"]!!.resolutionStatus)
        assertTrue(byName["fetchUser(id)"]!!.code.startsWith("fetchUser(id)"), "cross-file code snippet starts at the declaration name")
        assertTrue(byName["fetchUser(id)"]!!.filePath.contains("users.js"))
    }

    @Test
    fun `unresolved host API calls surface honestly`() {
        val text = """
            function render() {
              return useState(0);
            }
        """.trimIndent()

        val result = extractor().extractFlow("/synthetic/render.js", text, "render", includeModels = false)

        val byName = result.allNodes.filter { it.displayName == "useState()" }
        assertTrue(byName.isNotEmpty(), "useState is not declared anywhere, so it must surface as an unresolved node")
        assertEquals(ResolutionStatus.UNRESOLVED, byName.first().resolutionStatus)
        assertEquals(NodeKind.UNKNOWN, byName.first().kind)
    }

    @Test
    fun `unresolved node still points at a real source range`() {
        val text = """
            function start() {
              missingThing();
            }
        """.trimIndent()

        val result = extractor().extractFlow("/synthetic/app.js", text, "start", includeModels = false)

        val unresolved = result.allNodes.filter { it.resolutionStatus == ResolutionStatus.UNRESOLVED }
        assertTrue(unresolved.isNotEmpty(), "missingThing is not declared, so it must be kept as unresolved")
        for (node in unresolved) {
            assertTrue(node.range.startLine >= 1, "unresolved node must still have a real line range")
            assertTrue(node.displayName == "missingThing()")
        }
    }

    // ----------------------------------------------------------------------------------
    // TSX components
    // ----------------------------------------------------------------------------------

    @Test
    fun `TSX component resolves its inner async helper and the imported call`() {
        val result = extractor().extractFlow(
            filePath("UserProfileCard.tsx"),
            source("UserProfileCard.tsx"),
            "UserProfileCard",
            includeModels = false
        )

        val byName = result.allNodes.associateBy { it.displayName }
        assertNotNull(byName["fetchProfile()"], "imported fetchProfile resolves cross-file")
        assertEquals(ResolutionStatus.RESOLVED, byName["fetchProfile()"]!!.resolutionStatus)
        assertTrue(
            byName["fetchProfile()"]!!.code.startsWith("fetchProfile()"),
            "the resolved node must contain the real declaration source"
        )
        assertTrue(byName["fetchProfile()"]!!.filePath.contains("profileService.js"))
    }

    // ----------------------------------------------------------------------------------
    // Models
    // ----------------------------------------------------------------------------------

    @Test
    fun `models are reported only for real parsed declarations`() {
        val result = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", includeModels = true)

        val models = result.models
        assertTrue(models.isNotEmpty(), "UserProfile is imported and referenced in the module")
        assertTrue(models.any { it.displayName == "UserProfile" }, "the imported interface must be the model")
        for (model in models) {
            assertEquals(ResolutionStatus.RESOLVED, model.resolutionStatus)
            assertEquals(NodeKind.MODEL, model.kind)
            assertTrue(
                model.code.startsWith(model.displayName),
                "model code comes from the real declaration body"
            )
        }
    }

    @Test
    fun `models are absent when includeModels is false`() {
        val withModels = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", includeModels = true)
        assertTrue(withModels.models.isNotEmpty())

        val withoutModels = extractor().extractFlow(filePath("sample.js"), source("sample.js"), "buildGreeting", includeModels = false)
        assertTrue(withoutModels.models.isEmpty())
    }

    // ----------------------------------------------------------------------------------
    // Markdown output
    // ----------------------------------------------------------------------------------

    @Test
    fun `markdown renderer keeps config section and real node names`() {
        val markdown = extractor().extract(filePath("sample.js"), source("sample.js"), "outer", maxDepth = 1, includeModels = false)

        assertTrue(markdown.contains("# Flow Extracted by Bisotun"))
        assertTrue(markdown.contains("maxDepth=1"))
        assertTrue(markdown.contains("## Execution Flow Summary"))
        assertTrue(markdown.contains("`outer()`"))
        assertTrue(markdown.contains("`inner()`"), "inner must resolve and appear in markdown")
    }
}


