import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask

plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "ir.farzadafi"
version = "1.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    // IntelliJ platform fixtures are JUnit 3/4 based.
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")

    intellijPlatform {
        // Go PSI is only shipped by the JetBrains Go plugin. The plugin's descriptor states that
        // the only compatible IDE is IntelliJ IDEA Ultimate, so the Ultimate platform is the build
        // target (it also keeps the existing Java/JavaScript strategies compilable). GoLand bundles
        // the same Go plugin and is the runtime IDE for Go extraction.
        intellijIdeaUltimate("2024.1.4")
        bundledPlugin("com.intellij.java")
        plugin("org.jetbrains.plugins.go:241.17011.2")
        testFramework(TestFrameworkType.Platform)
        instrumentationTools()
        pluginVerifier()
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            ide("GO-2024.1.4")
            ide("GO-2024.2")
            ide("GO-2024.3")
        }
    }
}

tasks.named<PatchPluginXmlTask>("patchPluginXml") {
    untilBuild.set(provider { null })
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    // Go PSI cross-package tests configure a Go module, which starts the Go plugin's
    // external-system auto-import; each test class gets a fresh JVM so that tracker cannot
    // interfere with the shared light-fixture project of the next class.
    forkEvery = 1
}
