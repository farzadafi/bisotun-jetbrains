package com.bisotun.language

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the tokenizer strategy that backs JS/TS/JSX/TSX extraction in IntelliJ IDEA
 * Community, where no JavaScript PSI is available.
 *
 * They cover the two decisions the extractor cannot delegate to PSI there: which scope owns the
 * caret and which calls belong to a scope. Everything asserted here is pure text parsing, so no
 * platform fixture is required.
 */
class JsTsFlowExtractorTest {

    @Test
    fun `discovers nested async arrow inside typed React component`() {
        val outline = JsFileOutline.parse(TSX_SOURCE)

        assertEquals(listOf("UserProfileCard", "loadUserData"), outline.functions.map { it.name })
    }

    @Test
    fun `selects the innermost function at the caret`() {
        val outline = JsFileOutline.parse(TSX_SOURCE)

        val nestedCaret = TSX_SOURCE.indexOf("await userApi")
        assertEquals("loadUserData", outline.functionContaining(nestedCaret)?.name)

        val componentCaret = TSX_SOURCE.indexOf("return (<section>")
        assertEquals("UserProfileCard", outline.functionContaining(componentCaret)?.name)
    }

    @Test
    fun `caret outside any function is not assigned to a function`() {
        val outline = JsFileOutline.parse(TSX_SOURCE)

        assertNull(outline.functionContaining(0))
    }

    @Test
    fun `a caret inside a function is never demoted to the innermost block`() {
        val outline = JsFileOutline.parse(BLOCK_SOURCE)
        val caret = BLOCK_SOURCE.indexOf("doWork()")

        assertEquals("outer", outline.functionContaining(caret)?.name)

        val block = outline.blockContaining(caret)

        assertNotNull(block)
        val blockText = BLOCK_SOURCE.substring(block.startOffset, block.endOffset)
        assertTrue(blockText.startsWith("{"))
        assertTrue(blockText.contains("doWork()"))
    }

    @Test
    fun `module scope reports null block when the caret sits outside every brace`() {
        val outline = JsFileOutline.parse("startApp();\n")

        assertNull(outline.blockContaining(0))
    }

    @Test
    fun `module scope lists its own calls only`() {
        val outline = JsFileOutline.parse(MODULE_SOURCE)

        val moduleCalls = outline.callSitesIn(0, MODULE_SOURCE.length).mapRaw()

        assertEquals(listOf("startApp"), moduleCalls)
    }

    @Test
    fun `function scope still sees calls of nested functions`() {
        val outline = JsFileOutline.parse(MODULE_SOURCE)

        val allCalls = outline
            .callSitesIn(0, MODULE_SOURCE.length, skipNestedFunctions = false)
            .map { it.name }

        assertTrue("startApp" in allCalls)
        assertTrue("helper" in allCalls)
        assertTrue("loadProfile" in allCalls)
    }

    @Test
    fun `language kind falls back to the file extension for unparsed files`() {
        assertEquals(LanguageKind.TSX, LanguageKind.fromExtension("tsx"))
        assertEquals(LanguageKind.JSX, LanguageKind.fromExtension("jsx"))
        assertEquals(LanguageKind.TS, LanguageKind.fromExtension("ts"))
        assertEquals(LanguageKind.JS, LanguageKind.fromExtension("mjs"))
        assertEquals(LanguageKind.TS, LanguageKind.fromLanguageId("TypeScript"))
        assertEquals(LanguageKind.TSX, LanguageKind.fromLanguageId("TypeScript JSX"))
        assertEquals(LanguageKind.JSX, LanguageKind.fromLanguageId("JavaScript JSX"))
    }

    private fun List<JsCallSite>.mapRaw(): List<String> = map { it.rawText }

    private companion object {
        val TSX_SOURCE = """
            import { UserProfileDto } from './userTypes';

            export const UserProfileCard: React.FC = () => {
              const [user, setUser] = useState<UserProfileDto | null>(null);

              const loadUserData = async () => {
                const data = await userApi.fetchProfile();
                setUser(data);
              };

              return (<section>{user?.name}</section>);
            };
        """.trimIndent()

        val BLOCK_SOURCE = """
            function outer() {
              if (ready) {
                doWork();
              }
            }
        """.trimIndent()

        val MODULE_SOURCE = """
            const api = { fetchProfile: async () => 1 };

            export const build = () => {
              return helper();
            };

            const helper = () => api.loadProfile();

            startApp();
        """.trimIndent()
    }
}
