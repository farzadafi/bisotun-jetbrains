package com.bisotun.language

import com.bisotun.domain.FlowResult
import com.bisotun.domain.NodeKind
import com.bisotun.domain.ResolutionStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the C++ tokenizer strategy.
 *
 * IntelliJ IDEA Community has no C++ PSI (that support ships with CLion), so the plugin parses C++
 * with the same bounded tokenizer architecture used for JS/TS in Community and for Python. Everything
 * asserted here is pure text parsing plus the disk based include resolver, so the tests run on a plain
 * JVM without an IntelliJ fixture.
 */
class CppFlowExtractorTest {

    private val fixtureDir: File by lazy {
        val url = checkNotNull(javaClass.classLoader.getResource("fixtures/cpp")) {
            "fixtures/cpp must exist on the test classpath"
        }
        File(url.toURI()).canonicalFile
    }

    private fun fixturePath(name: String): String = File(fixtureDir, name).absolutePath

    private fun fixtureText(name: String): String = File(fixtureDir, name).readText()

    private fun extractCpp(
        fileName: String,
        caretSnippet: String,
        maxDepth: Int = 1,
        includeModels: Boolean = true
    ): FlowResult {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath(fileName), LanguageKind.CPP)
            ?: error("$fileName must be readable")
        val caret = source.text.indexOf(caretSnippet)
        assertTrue(caret >= 0, "caret snippet `$caretSnippet` not found in $fileName")
        return CppFlowExtractor.forAnalysis(fixtureDir.absolutePath)
            .extractFromSource(source, caret, maxDepth, includeModels, context)
            ?: error("extraction failed for $fileName")
    }

    private fun resolveSite(
        fileName: String,
        functionName: String,
        rawText: String
    ): CppResolvedCall? {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath(fileName), LanguageKind.CPP)
            ?: error("$fileName must be readable")
        val function = source.outline.functions.first { it.name == functionName }
        val site = source.outline.callSitesIn(function).firstOrNull { it.rawText == rawText }
            ?: error("call `$rawText` not found in $functionName: ${source.outline.callSitesIn(function).map { it.rawText }}")
        return CppCallResolver().resolve(source, site, context.resolutionSources(source))
    }

    // ----------------------------------------------------------------------------------
    // Language detection
    // ----------------------------------------------------------------------------------

    @Test
    fun `language kind covers every required cpp extension`() {
        listOf("cpp", "cc", "cxx", "h", "hh", "hpp", "hxx").forEach { extension ->
            assertEquals(LanguageKind.CPP, LanguageKind.fromExtension(extension), extension)
        }
        assertEquals("C++", LanguageKind.CPP.displayName)
        assertEquals("cpp", LanguageKind.CPP.codeFence)
        assertEquals(LanguageKind.CPP, LanguageKind.fromLanguageId("C++"))
        assertEquals(LanguageKind.CPP, LanguageKind.fromLanguageId("cpp"))
    }

    @Test
    fun `other languages keep their own detection`() {
        assertEquals(LanguageKind.JAVA, LanguageKind.fromExtension("java"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("py"))
        assertEquals(LanguageKind.TS, LanguageKind.fromExtension("ts"))
        assertEquals(LanguageKind.UNKNOWN, LanguageKind.fromExtension("rs"))
    }

    // ----------------------------------------------------------------------------------
    // Namespaces, classes, inheritance
    // ----------------------------------------------------------------------------------

    @Test
    fun `parses namespaces classes and inheritance`() {
        val outline = CppFileOutline.parse(fixtureText("engine.hpp"))

        val engine = outline.typeNamed("Engine") ?: error("Engine must be parsed")
        assertEquals("core::Engine", engine.qualifiedName)
        assertEquals("class", engine.keyword)
        assertEquals(listOf("Base"), engine.bases)
        assertTrue(engine.hasBody)
        assertEquals(listOf("typename T"), engine.templateParams)

        val base = outline.typeNamed("Base") ?: error("Base must be parsed")
        assertEquals("core::Base", base.qualifiedName)

        assertTrue(outline.namespaces.any { it.qualifiedName == "core" })
    }

    @Test
    fun `parses structs and their members`() {
        val outline = CppFileOutline.parse(
            """
            struct Point {
                int x;
                int y;
            };

            struct Circle : public Point {
                double radius;
                void draw();
            };
            """.trimIndent()
        )

        val point = outline.typeNamed("Point") ?: error("Point must be parsed")
        assertEquals("struct", point.keyword)
        assertTrue(point.fields.any { it.name == "x" && it.typeName == "int" })
        assertTrue(point.fields.any { it.name == "y" })

        val circle = outline.typeNamed("Circle") ?: error("Circle must be parsed")
        assertEquals(listOf("Point"), circle.bases)
        assertTrue(circle.fields.any { it.name == "radius" && it.typeName == "double" })
        assertEquals(listOf("draw"), outline.methodsIn(circle).map { it.name })
    }

    @Test
    fun `class scope and method scope are selected by offset`() {
        val text = fixtureText("engine.hpp")
        val outline = CppFileOutline.parse(text)

        val fieldOffset = text.indexOf("int id_")
        val classScope = outline.scopeAt(fieldOffset)
        assertTrue(classScope is CppScope.TypeScope, "a caret on a member belongs to the class")
        assertEquals("core::Engine", classScope.type.qualifiedName)

        val mainText = fixtureText("main.cpp")
        val mainOutline = CppFileOutline.parse(mainText)
        val bodyOffset = mainText.indexOf("attempts += compute")
        val methodScope = mainOutline.scopeAt(bodyOffset)
        assertTrue(methodScope is CppScope.Function, "a caret in a body belongs to the method")
        assertEquals("core::Engine", methodScope.definition.ownerType)
    }

    // ----------------------------------------------------------------------------------
    // Functions, constructors, destructors, parameters
    // ----------------------------------------------------------------------------------

    @Test
    fun `parses constructors destructors methods and parameters`() {
        val outline = CppFileOutline.parse(fixtureText("engine.hpp"))
        val engine = outline.typeNamed("Engine") ?: error("Engine must be parsed")

        val constructor = outline.methodsIn(engine).firstOrNull { it.kind == CppFunctionKind.CONSTRUCTOR }
        assertNotNull(constructor, "explicit Engine(int id) must be a constructor")
        assertEquals("core::Engine::Engine", constructor.qualifiedName)
        assertEquals(listOf("int"), constructor.parameters.map { it.typeName })
        assertEquals(listOf("id"), constructor.parameters.map { it.name })
        assertFalse(constructor.hasBody, "a header-only constructor declaration has no body")

        val destructor = outline.methodsIn(engine).firstOrNull { it.kind == CppFunctionKind.DESTRUCTOR }
        assertNotNull(destructor, "~Engine() must be a destructor")
        assertEquals("~Engine", destructor.name)
        assertEquals("core::Engine::~Engine", destructor.qualifiedName)

        val start = outline.methodIn(engine, "start")
        assertNotNull(start, "start(int retries) must be a method")
        assertEquals(CppFunctionKind.METHOD, start.kind)
        assertEquals(NodeKind.METHOD, start.nodeKind)
        assertEquals("void", start.returnType)
        assertEquals("void core::Engine::start(int retries)", start.displayName)
        assertEquals("int retries", start.parameters.single().let { "${it.typeName} ${it.name}" })
    }

    @Test
    fun `parses free functions and local declarations`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)

        val compute = outline.functionNamed("compute") ?: error("compute must be parsed")
        assertEquals(CppFunctionKind.FUNCTION, compute.kind)
        assertEquals("static int core::compute(int value)", compute.displayName)
        assertEquals("core", compute.namespacePath)
        assertTrue(compute.hasBody)

        val start = outline.functions.first { it.name == "start" }
        val environment = outline.typeEnvironment(start)
        assertEquals("Engine", environment["engine"])
        assertEquals("int", environment["attempts"])

        val declarations = outline.localDeclarations(start).map { it.displayName }
        assertTrue(declarations.any { it.startsWith("int attempts = 0") })
        assertTrue(declarations.any { it.startsWith("Engine* engine = new Engine(7)") })
    }

    // ----------------------------------------------------------------------------------
    // Control flow and exceptions
    // ----------------------------------------------------------------------------------

    @Test
    fun `extracts conditions loops switch and exceptions`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val start = outline.functions.first { it.name == "start" }
        val statements = outline.controlFlowStatements(start).map { it.displayName }

        assertTrue(statements.contains("if (retries > 0)"), statements.toString())
        assertTrue(statements.contains("else"), statements.toString())
        assertTrue(statements.any { it.startsWith("for (int i = 0") }, statements.toString())
        assertTrue(statements.contains("while (attempts < 3)"), statements.toString())
        assertTrue(statements.contains("switch (attempts)"), statements.toString())
        assertTrue(statements.contains("case 0:"), statements.toString())
        assertTrue(statements.contains("default:"), statements.toString())
        assertTrue(statements.contains("do"), statements.toString())
        assertTrue(statements.any { it.startsWith("for (const auto& item") }, statements.toString())
        assertTrue(statements.contains("try"), statements.toString())
        assertTrue(statements.any { it.startsWith("catch (") }, statements.toString())
        assertTrue(statements.any { it.startsWith("throw ") }, statements.toString())

        val compute = outline.functionNamed("compute") ?: error("compute must be parsed")
        val computeStatements = outline.controlFlowStatements(compute).map { it.displayName }
        assertTrue(computeStatements.contains("return total"), computeStatements.toString())
    }

    @Test
    fun `control flow nodes keep real source ranges`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val start = outline.functions.first { it.name == "start" }

        for (statement in outline.controlFlowStatements(start)) {
            assertTrue(statement.startOffset >= start.startOffset)
            assertTrue(statement.endOffset <= start.endOffset)
            assertTrue(statement.endOffset > statement.startOffset)
            assertTrue(text.substring(statement.startOffset, statement.endOffset).isNotBlank())
        }
    }

    // ----------------------------------------------------------------------------------
    // Calls and resolution
    // ----------------------------------------------------------------------------------

    @Test
    fun `collects calls and skips declarations`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val start = outline.functions.first { it.name == "start" }
        val sites = outline.callSitesIn(start)

        val rawCalls = sites.map { it.rawText }
        assertTrue(rawCalls.contains("compute"), rawCalls.toString())
        assertTrue(rawCalls.contains("engine->start"), rawCalls.toString())
        assertTrue(rawCalls.contains("retry"), rawCalls.toString())
        assertTrue(
            rawCalls.none { it == "this->start" },
            "calls inside a nested lambda are not top-level sites of the enclosing method: $rawCalls"
        )
        assertTrue(sites.none { it.offset == start.nameOffset }, "the declaration itself is not a call")
    }

    @Test
    fun `same file call resolves to the real function`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val source = CppFlowSource(fixturePath("main.cpp"), text, outline, LanguageKind.CPP)
        val start = outline.functions.first { it.name == "start" }
        val site = outline.callSitesIn(start).first { it.rawText == "compute" }

        val resolved = CppCallResolver().resolve(source, site, emptyList())

        assertNotNull(resolved)
        assertEquals("core::compute", resolved.definition.qualifiedName)
        assertEquals("compute", resolved.definition.name)
        assertEquals(source, resolved.source)
    }

    @Test
    fun `member call resolves through a local variable type and quoted include`() {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("main.cpp"), LanguageKind.CPP)
            ?: error("main.cpp must be readable")
        val includes = context.includedSources(source)
        assertEquals(listOf("engine.hpp"), includes.map { File(it.filePath).name })

        val start = source.outline.functions.first { it.name == "start" }
        val site = source.outline.callSitesIn(start).first { it.rawText == "engine->start" }

        val resolved = CppCallResolver().resolve(source, site, includes)

        assertNotNull(resolved)
        assertEquals("core::Engine::start", resolved.definition.qualifiedName)
        assertTrue(resolved.definition.hasBody, "the out-of-line definition is preferred over the header prototype")
        assertTrue(resolved.source.filePath.endsWith("main.cpp"))
    }

    @Test
    fun `this call inside a lambda resolves through the enclosing method owner`() {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("main.cpp"), LanguageKind.CPP)
            ?: error("main.cpp must be readable")
        val includes = context.includedSources(source)

        val retry = source.outline.lambdas.first { it.name == "retry" }
        val site = source.outline.callSitesIn(retry).first { it.rawText == "this->start" }

        val resolved = CppCallResolver().resolve(source, site, includes)

        assertNotNull(resolved)
        assertEquals("core::Engine::start", resolved.definition.qualifiedName)
    }

    @Test
    fun `call to a lambda variable resolves to the lambda declaration`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val source = CppFlowSource(fixturePath("main.cpp"), text, outline, LanguageKind.CPP)
        val start = outline.functions.first { it.name == "start" }
        val site = outline.callSitesIn(start).first { it.rawText == "retry" }

        val resolved = CppCallResolver().resolve(source, site, emptyList())

        assertNotNull(resolved)
        assertEquals(CppFunctionKind.LAMBDA, resolved.definition.kind)
        assertEquals("retry", resolved.definition.name)
    }

    @Test
    fun `qualified static call resolves to the declared method`() {
        val text = """
            namespace core {
            class Engine {
            public:
                static Engine create(int id);
            };
            void run() {
                Engine::create(1);
            }
            }
        """.trimIndent()
        val outline = CppFileOutline.parse(text)
        val source = CppFlowSource("/synthetic/engine.cpp", text, outline, LanguageKind.CPP)
        val run = outline.functionNamed("run") ?: error("run must be parsed")
        val site = outline.callSitesIn(run).firstOrNull { it.rawText == "Engine::create" }
        assertNotNull(site, outline.callSitesIn(run).map { it.rawText }.toString())

        val resolved = CppCallResolver().resolve(source, site, emptyList())

        assertNotNull(resolved)
        assertEquals("core::Engine::create", resolved.definition.qualifiedName)
    }

    @Test
    fun `unresolvable calls stay unresolved instead of inventing a target`() {
        val text = """
            void run() {
                externalClient.fetch();
            }
        """.trimIndent()
        val outline = CppFileOutline.parse(text)
        val source = CppFlowSource("/synthetic/app.cpp", text, outline, LanguageKind.CPP)
        val run = outline.functionNamed("run") ?: error("run must be parsed")
        val site = outline.callSitesIn(run).single()

        assertNull(CppCallResolver().resolve(source, site, emptyList()))
    }

    // ----------------------------------------------------------------------------------
    // Templates and lambdas
    // ----------------------------------------------------------------------------------

    @Test
    fun `templates keep their parameter lists`() {
        val outline = CppFileOutline.parse(
            """
            template <typename T, int N>
            class Stack {
            public:
                T top() const;
                void push(const T& value);
            };

            template <typename T>
            T maxValue(T left, T right) {
                return left > right ? left : right;
            }
            """.trimIndent()
        )

        val stack = outline.typeNamed("Stack") ?: error("Stack must be parsed")
        assertEquals(listOf("typename T", "int N"), stack.templateParams)

        val top = outline.methodIn(stack, "top") ?: error("top must be parsed")
        assertEquals("T", top.returnType)

        val maxValue = outline.functionNamed("maxValue") ?: error("maxValue must be parsed")
        assertEquals(listOf("typename T"), maxValue.templateParams)
        assertTrue(maxValue.displayName.startsWith("template<typename T> T maxValue"), maxValue.displayName)
    }

    @Test
    fun `lambdas are discovered and own the caret inside their body`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)

        val retry = outline.lambdas.firstOrNull { it.name == "retry" }
        assertNotNull(retry, outline.lambdas.map { it.displayName }.toString())
        assertEquals(CppFunctionKind.LAMBDA, retry.kind)
        assertEquals("retry(int step)", retry.displayName)
        assertTrue(retry.hasBody)

        val caret = text.indexOf("this->start(step)")
        val containing = outline.functionContaining(caret)
        assertNotNull(containing)
        assertEquals("retry", containing.name)
        assertEquals(NodeKind.FUNCTION, containing.nodeKind)
    }

    // ----------------------------------------------------------------------------------
    // Includes, object creation, noise filtering, robustness
    // ----------------------------------------------------------------------------------

    @Test
    fun `includes are parsed and quoted headers resolve on disk`() {
        val outline = CppFileOutline.parse(fixtureText("main.cpp"))
        val engineInclude = outline.includes.first { it.target == "engine.hpp" }
        assertFalse(engineInclude.isSystem)
        assertEquals("#include \"engine.hpp\"", engineInclude.displayName)
        assertTrue(outline.includes.any { it.target == "vector" && it.isSystem })

        val context = CppAnalysisContext(fixtureDir.absolutePath)
        assertEquals(
            fixturePath("engine.hpp"),
            context.resolveInclude(fixturePath("main.cpp"), engineInclude)
        )
        assertNull(
            context.resolveInclude(fixturePath("main.cpp"), CppInclude("", "vector", true, 0, 0)),
            "system headers must never be followed"
        )
    }

    @Test
    fun `object creation and its constructor are discoverable`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val start = outline.functions.first { it.name == "start" }

        val creations = outline.objectCreationsIn(start)

        assertEquals(1, creations.size)
        assertEquals("Engine", creations.single().typeName)
        assertTrue(text.substring(creations.single().offset, creations.single().endOffset).startsWith("new Engine"))
    }

    @Test
    fun `standard library macros and accessors are filtered as noise`() {
        val text = """
            void run() {
                std::vector<int> values;
                values.push_back(1);
                printf("hi");
                ASSERT_OK(1);
                auto text = std::to_string(1);
            }
        """.trimIndent()
        val outline = CppFileOutline.parse(text)
        val run = outline.functions.first { it.name == "run" }
        val collected = outline.callSitesIn(run).map { it.rawText }

        assertTrue(collected.none { it.contains("printf") }, collected.toString())
        assertTrue(collected.none { it.contains("to_string") }, collected.toString())
        assertTrue(collected.none { it.contains("ASSERT_OK") }, collected.toString())
    }

    @Test
    fun `malformed input never throws and never fabricates a scope`() {
        val outline = CppFileOutline.parse(fixtureText("malformed.cpp"))
        assertNotNull(outline)
        assertNull(CppFileOutline.parse("").scopeAt(0))
        assertNull(CppFileOutline.parse("// only a comment\n").scopeAt(0))
        // Incomplete declarations must still yield real, bounded ranges or nothing at all.
        outline.functions.forEach {
            assertTrue(it.startOffset >= 0)
            assertTrue(it.endOffset >= it.startOffset)
        }
    }

    @Test
    fun `dereferenced unresolved node data is honest for the extractor contract`() {
        val text = fixtureText("main.cpp")
        val outline = CppFileOutline.parse(text)
        val source = CppFlowSource(fixturePath("main.cpp"), text, outline, LanguageKind.CPP)
        val includes = CppAnalysisContext(fixtureDir.absolutePath).includedSources(source)
        val run = outline.functions.first { it.name == "start" }

        val unresolved = outline.callSitesIn(run)
            .filter { CppCallResolver().resolve(source, it, includes) == null }
            .map { it.rawText }

        assertTrue(unresolved.contains("use"), unresolved.toString())
        assertEquals(NodeKind.FUNCTION, NodeKind.FUNCTION)
        assertEquals(ResolutionStatus.UNRESOLVED.name, "UNRESOLVED")
    }

    // ----------------------------------------------------------------------------------
    // Production-hardening: same-file, header/source, templates, receivers, lambdas
    // ----------------------------------------------------------------------------------

    @Test
    fun `same file out of line method defined later is resolved`() {
        val resolved = resolveSite("flow_sample.cpp", "run", "runOne")

        assertNotNull(resolved)
        assertEquals("Pipeline::runOne", resolved.definition.qualifiedName)
        assertTrue(resolved.definition.hasBody)
        assertTrue(resolved.source.filePath.endsWith("flow_sample.cpp"))
        assertEquals(CppFunctionKind.METHOD, resolved.definition.kind)
    }

    @Test
    fun `header declaration plus source definition is resolved across the include`() {
        val resolved = resolveSite("flow_sample.cpp", "run", "runOne")
        assertNotNull(resolved)

        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("flow_sample.cpp"), LanguageKind.CPP)
            ?: error("flow_sample.cpp must be readable")
        val header = context.resolutionSources(source).first { it.filePath.endsWith("flow_sample.hpp") }
        val pipeline = header.outline.typeNamed("Pipeline") ?: error("Pipeline must be parsed in the header")
        val declaration = header.outline.methodIn(pipeline, "runOne")
        assertNotNull(declaration, "the header still holds the prototype")
        assertFalse(declaration.hasBody)
        assertEquals(declaration.qualifiedName, resolved.definition.qualifiedName)
        assertTrue(resolved.definition.hasBody, "resolution prefers the source definition")
    }

    @Test
    fun `quoted project include is used to resolve a template method`() {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("flow_sample.cpp"), LanguageKind.CPP)
            ?: error("flow_sample.cpp must be readable")
        val includes = context.includedSources(source)
        assertTrue(includes.any { File(it.filePath).name == "flow_sample.hpp" }, includes.map { it.filePath }.toString())

        val resolved = resolveSite("flow_sample.cpp", "run", "jobs_.filter")
        assertNotNull(resolved)
        assertEquals("Repository::filter", resolved.definition.qualifiedName)
        assertTrue(resolved.source.filePath.endsWith("flow_sample.hpp"))
        assertTrue(resolved.definition.hasBody)
        assertTrue(resolved.definition.templateParams.isEmpty())
        val repository = resolved.source.outline.typeNamed("Repository") ?: error("Repository must be parsed")
        assertEquals(listOf("typename T"), repository.templateParams)
    }

    @Test
    fun `receiver member call resolves through a known unique_ptr field type`() {
        val resolved = resolveSite("flow_sample.cpp", "run", "worker_->execute")

        assertNotNull(resolved)
        assertEquals("Worker::execute", resolved.definition.qualifiedName)
        assertTrue(resolved.definition.hasBody)
        assertTrue(resolved.source.filePath.endsWith("flow_sample.cpp"))
    }

    @Test
    fun `same named methods on unrelated classes and namespaces stay distinct`() {
        val resolved = resolveSite("flow_sample.cpp", "run", "runOne")
        assertNotNull(resolved)
        assertEquals("Pipeline::runOne", resolved.definition.qualifiedName)
        assertFalse(resolved.definition.ownerType == "Unrelated")
        assertFalse(resolved.definition.namespacePath == "other")

        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("flow_sample.cpp"), LanguageKind.CPP)
            ?: error("flow_sample.cpp must be readable")
        val search = context.resolutionSources(source)
        val unrelated = source.outline.functions.first { it.ownerType == "Unrelated" && it.name == "runOne" }
        assertTrue(search.any { it.outline.functions.any { fn -> fn.qualifiedName == unrelated.qualifiedName } })
        assertTrue(cppOwnersCompatible("Pipeline", "Pipeline"))
        assertFalse(cppOwnersCompatible("Unrelated", "Pipeline"))
        assertFalse(cppOwnersCompatible("other::Pipeline", "Pipeline"))
        assertEquals("Pipeline::runOne", resolved.definition.qualifiedName)
    }

    @Test
    fun `lambda predicate body is not emitted as a top level execution step`() {
        val text = fixtureText("flow_sample.cpp")
        val outline = CppFileOutline.parse(text)
        val run = outline.functions.first { it.name == "run" && it.ownerType == "Pipeline" }

        val statements = outline.controlFlowStatements(run).map { it.displayName }
        assertTrue(statements.none { it.contains("job.state") }, statements.toString())
        assertTrue(statements.none { it.contains("JobState::Pending") }, statements.toString())
        assertTrue(statements.any { it.startsWith("try") }, statements.toString())

        val sites = outline.callSitesIn(run).map { it.rawText }
        assertTrue(sites.contains("jobs_.filter"), sites.toString())
        assertTrue(sites.contains("runOne"), sites.toString())
        assertTrue(sites.contains("worker_->execute"), sites.toString())

        val result = extractCpp("flow_sample.cpp", "runOne();", maxDepth = 1, includeModels = false)
        val names = result.allNodes.map { it.displayName }
        assertTrue(
            names.none { it.startsWith("return") && it.contains("job.state") },
            "predicate return must not be a standalone execution step: $names"
        )
        assertTrue(result.nodes.any { it.displayName.contains("Pipeline::runOne") }, names.toString())
        assertTrue(result.nodes.any { it.displayName.contains("Repository::filter") }, names.toString())
        assertTrue(result.nodes.none { it.kind == NodeKind.FUNCTION && it.displayName.startsWith("lambda") }, names.toString())
        val pending = names.firstOrNull { it.startsWith("auto pending") }
        assertNotNull(pending, "the filter call stays attached to its local: $names")
        assertTrue(pending.contains("jobs_.filter"), pending)
    }

    @Test
    fun `standard library exception what is filtered as unresolved noise`() {
        val result = extractCpp("flow_sample.cpp", "runOne();", maxDepth = 1, includeModels = false)
        val names = result.allNodes.map { it.displayName }
        assertTrue(names.none { it.contains("what") }, names.toString())
        assertTrue(names.none { it.contains("error.what") }, names.toString())
        assertTrue(names.none { it.contains("front") }, names.toString())

        val source = CppAnalysisContext(fixtureDir.absolutePath)
            .sourceForPath(fixturePath("flow_sample.cpp"), LanguageKind.CPP)
            ?: error("flow_sample.cpp must be readable")
        val run = source.outline.functions.first { it.name == "run" && it.ownerType == "Pipeline" }
        val what = source.outline.callSitesIn(run).firstOrNull { it.name == "what" || it.rawText.contains("what") }
        if (what != null) {
            assertNull(CppCallResolver().resolve(source, what, emptyList()))
            assertTrue(cppIsUnresolvedNoise(what))
        }
    }

    @Test
    fun `calls without a source declaration stay unresolved`() {
        val resolved = resolveSite("overloads.cpp", "runCalculator", "unknownCall")
        assertNull(resolved)

        val result = extractCpp("overloads.cpp", "unknownCall();", maxDepth = 1, includeModels = false)
        val unknown = result.nodes.firstOrNull { it.displayName.contains("unknownCall") }
        assertNotNull(unknown, result.nodes.map { it.displayName }.toString())
        assertEquals(ResolutionStatus.UNRESOLVED, unknown.resolutionStatus)
        assertTrue(unknown.range.startLine >= 1)
        assertTrue(unknown.range.endLine >= unknown.range.startLine)
    }

    @Test
    fun `overloads resolve only when arity matches uniquely`() {
        val one = resolveSite("overloads.cpp", "runCalculator", "calculator.compute")
        assertNotNull(one)
        assertEquals(1, one.definition.parameters.size)
        assertEquals("Calculator::compute", one.definition.qualifiedName)

        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("overloads.cpp"), LanguageKind.CPP)
            ?: error("overloads.cpp must be readable")
        val run = source.outline.functions.first { it.name == "runCalculator" }
        val sites = source.outline.callSitesIn(run).filter { it.rawText == "calculator.compute" }
        assertEquals(2, sites.size, sites.map { "${it.rawText}/${it.argumentCount}" }.toString())
        val twoArg = sites.first { it.argumentCount == 2 }
        val resolvedTwo = CppCallResolver().resolve(source, twoArg, context.resolutionSources(source))
        assertNotNull(resolvedTwo)
        assertEquals(2, resolvedTwo.definition.parameters.size)
    }

    @Test
    fun `extracted flow is deterministic and keeps source locations`() {
        val first = extractCpp("flow_sample.cpp", "runOne();", maxDepth = 2, includeModels = true)
        val second = extractCpp("flow_sample.cpp", "runOne();", maxDepth = 2, includeModels = true)

        assertEquals(first.root.displayName, second.root.displayName)
        assertEquals(first.nodes.map { it.id to it.displayName }, second.nodes.map { it.id to it.displayName })
        assertEquals(first.models.map { it.displayName }, second.models.map { it.displayName })

        assertTrue(first.root.range.startLine >= 1)
        assertTrue(first.root.code.contains("void Pipeline::run()"))
        for (node in first.allNodes) {
            assertTrue(node.range.startLine >= 1, node.displayName)
            assertTrue(node.range.endLine >= node.range.startLine, node.displayName)
            assertTrue(node.filePath.endsWith(".cpp") || node.filePath.endsWith(".hpp"), node.filePath)
        }
        assertTrue(first.nodes.any { it.resolutionStatus == ResolutionStatus.RESOLVED && it.displayName.contains("runOne") })
        assertTrue(first.models.any { it.displayName.contains("Pipeline") || it.displayName.contains("Repository") })
    }

    @Test
    fun `include directives are not executable steps of a function`() {
        val result = extractCpp("flow_sample.cpp", "runOne();", maxDepth = 1, includeModels = false)
        assertTrue(
            result.allNodes.none { it.displayName.startsWith("#include") },
            result.allNodes.map { it.displayName }.toString()
        )
    }

    @Test
    fun `malformed cpp still extracts without throwing`() {
        val context = CppAnalysisContext(fixtureDir.absolutePath)
        val source = context.sourceForPath(fixturePath("malformed.cpp"), LanguageKind.CPP)
            ?: error("malformed.cpp must be readable")
        val result = CppFlowExtractor.forAnalysis(fixtureDir.absolutePath)
            .extractFromSource(source, 0, 1, true, context)
        assertTrue(result == null || result.allNodes.isNotEmpty() || result.warnings.isNotEmpty())
        val outline = CppFileOutline.parse("void broken( { if (")
        assertNotNull(outline)
        assertTrue(outline.functions.all { it.endOffset >= it.startOffset })
    }

    @Test
    fun `cpp extractor does not claim other languages`() {
        val extractor = CppFlowExtractor.forAnalysis(fixtureDir.absolutePath)
        assertEquals(setOf(LanguageKind.CPP), extractor.supportedLanguages)
        assertFalse(extractor.supports(LanguageKind.JAVA))
        assertFalse(extractor.supports(LanguageKind.PYTHON))
        assertFalse(extractor.supports(LanguageKind.JS))
        assertFalse(extractor.supports(LanguageKind.TS))
        assertEquals(LanguageKind.JAVA, LanguageKind.fromExtension("java"))
        assertEquals(LanguageKind.PYTHON, LanguageKind.fromExtension("py"))
        assertEquals(LanguageKind.JS, LanguageKind.fromExtension("js"))
        assertEquals(LanguageKind.TS, LanguageKind.fromExtension("ts"))
    }
}
