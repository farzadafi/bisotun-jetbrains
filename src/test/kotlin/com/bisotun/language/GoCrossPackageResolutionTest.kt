package com.bisotun.language

import com.bisotun.domain.FlowResult
import com.bisotun.domain.ResolutionStatus
import com.goide.project.GoModuleSettings
import com.goide.sdk.GoSdk
import com.goide.sdk.GoSdkService
import com.goide.vgo.VgoModuleInfoProviderForTests
import com.goide.vgo.project.VgoDependency
import com.goide.vgo.project.VgoModule
import com.goide.vgo.project.VgoModulesRegistry
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * Cross-package Go resolution against real Go PSI.
 *
 * The Go plugin resolves module imports only when the project has a Go SDK and a registered Go
 * module. Registering a module starts the plugin's external-system auto-import tracking, which is
 * not usable from a shared light fixture that keeps editing files; this class therefore contains a
 * single test and the `test` task forks a JVM per test class (see `build.gradle.kts`).
 */
class GoCrossPackageResolutionTest : BasePlatformTestCase() {

    private lateinit var files: MutableMap<String, PsiFile>

    override fun setUp() {
        super.setUp()
        files = LinkedHashMap()
        installFixtureSdk()
        addFile("go.mod", "module example.com/bisotun\n\ngo 1.21\n")
        addFile("service/order_service.go", resource("service/order_service.go"))
        addFile("service/order_repository.go", resource("service/order_repository.go"))
        addFile("notify/notifier.go", resource("notify/notifier.go"))
        addFile("cmd/main.go", resource("cmd/main.go"))
        registerGoModule()
    }

    fun testCrossPackageCallsResolveThroughGoPsi() {
        val main = extract("cmd/main.go", "println(order.ID)", maxDepth = 2)

        val constructor = main.nodes.firstOrNull { it.displayName.startsWith("NewOrderService(") }
            ?: error("imported constructor must resolve: ${main.names()}")
        assertTrue(constructor.filePath.endsWith("order_service.go"))
        assertEquals(ResolutionStatus.RESOLVED, constructor.resolutionStatus)

        val create = main.nodes.firstOrNull { it.displayName.startsWith("OrderService.Create(") }
            ?: error("method on an imported constructor result must resolve: ${main.names()}")
        assertTrue(create.filePath.endsWith("order_service.go"))
        assertEquals(ResolutionStatus.RESOLVED, create.resolutionStatus)

        val chain = extract("service/order_service.go", "notify.NewEmailNotifier", maxDepth = 2)
        val notifier = chain.nodes.firstOrNull { it.displayName.startsWith("EmailNotifier.Notify(") }
            ?: error("chained imported constructor call must resolve: ${chain.names()}")
        assertTrue(notifier.filePath.endsWith("notifier.go"))
        assertEquals(ResolutionStatus.RESOLVED, notifier.resolutionStatus)
    }

    // ----------------------------------------------------------------------------------
    // Fixture configuration
    // ----------------------------------------------------------------------------------

    /**
     * A minimal GOROOT (VERSION file location used by the Go plugin + executable stub) makes the
     * fixture module a real Go module. It is configured before any Go file is added so the Go
     * stub index is built with the final SDK version instead of requiring a rebuild.
     */
    private fun installFixtureSdk() {
        val home = File(FileUtil.getTempDirectory(), "bisotun-fixture-goroot")
        File(home, "bin").mkdirs()
        File(home, "src/internal/buildcfg").mkdirs()
        File(home, "pkg").mkdirs()
        File(home, "src/internal/buildcfg/zbootstrap.go").writeText(
            "package buildcfg\n\nconst theVersion = \"go1.21.5\"\n"
        )
        File(home, "bin/go").apply {
            writeText("#!/bin/sh\necho 'go version go1.21.5 linux/amd64'\n")
            setExecutable(true)
        }
        val sdkRoot = LocalFileSystem.getInstance().refreshAndFindFileByPath(home.absolutePath)
            ?: error("fixture GOROOT must be visible to the VFS")
        GoSdkService.getInstance(project).setSdk(GoSdk.fromHomePath(sdkRoot.path))
        GoModuleSettings.getInstance(myFixture.module).isGoSupportEnabled = true
    }

    /**
     * Registers the fixture `go.mod` in the Go plugin's module registry, so an import resolves to
     * the project package through Go PSI instead of being matched by name.
     */
    private fun registerGoModule() {
        val root = files.getValue("go.mod").virtualFile?.parent ?: error("go.mod must be on disk")
        val vgoModule = FixtureVgoModule(root, "example.com/bisotun", "")
        VgoModuleInfoProviderForTests.putTestingVgoModule(vgoModule, testRootDisposable)
        VgoModulesRegistry.getInstance(project)
            .updateModules(module, listOf(com.goide.vgo.VgoRootToModule(root, vgoModule)))
    }

    private class FixtureVgoModule(
        root: VirtualFile,
        importPath: String,
        version: String
    ) : VgoModule(root, importPath, version) {
        override fun getDependencies(): Collection<VgoDependency> = emptyList()
        override fun findDependencyAt(importPath: String): VgoDependency? = null
        override fun dependenciesAreUnknown(): Boolean = false
    }

    // ----------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------

    private fun addFile(path: String, text: String) {
        files[path] = myFixture.addFileToProject(path, text)
    }

    private fun resource(path: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/go/$path")) {
            "fixtures/go/$path must exist on the test classpath"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun extract(path: String, caretSnippet: String, maxDepth: Int): FlowResult {
        val file = files.getValue(path)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val caret = file.text.indexOf(caretSnippet)
        assertTrue("caret snippet `$caretSnippet` not found in $path", caret >= 0)
        myFixture.editor.caretModel.moveToOffset(caret)

        val outcome = GoFlowExtractor(project).extractFlow(file, myFixture.editor, maxDepth, false)
        assertTrue("extraction failed: ${outcome.message}", outcome.isSuccess)
        return checkNotNull(outcome.result)
    }

    private fun FlowResult.names(): List<String> = allNodes.map { it.displayName }
}
