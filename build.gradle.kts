import org.jetbrains.intellij.platform.gradle.Constants.Configurations
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")

    intellijPlatform {
        intellijIdeaUltimate("2024.1.4")
        bundledPlugin("com.intellij.java")
        plugin("org.jetbrains.plugins.go:241.17011.2")
        testFramework(TestFrameworkType.Platform)
        instrumentationTools()
        pluginVerifier()
    }
}

// Each Plugin Verifier IDE is its own resolvable configuration, created with the
// configuration factory method. That keeps every product version on the verification
// classpath. ide("TYPE-VERSION") registers those configurations through
// ConfigurationContainer.addAllLater, which Gradle 8.13 deprecates and Gradle 9 removes.
val pluginVerifierIdes = listOf(
    // GoLand
    IntelliJPlatformType.GoLand to "2024.1.4",
    IntelliJPlatformType.GoLand to "2024.3",
    IntelliJPlatformType.GoLand to "2025.3",
    IntelliJPlatformType.GoLand to "2026.1",
    // WebStorm (برای JS/TS)
    IntelliJPlatformType.WebStorm to "2024.3",
    IntelliJPlatformType.WebStorm to "2025.3",
    IntelliJPlatformType.WebStorm to "2026.1",
    // PyCharm Professional (برای پایتون)
    IntelliJPlatformType.PyCharmProfessional to "2024.3",
    IntelliJPlatformType.PyCharmProfessional to "2025.3",
    IntelliJPlatformType.PyCharmProfessional to "2026.1",
    // CLion (برای C/C++)
    IntelliJPlatformType.CLion to "2024.3",
    IntelliJPlatformType.CLion to "2025.3",
    IntelliJPlatformType.CLion to "2026.1",
    // IntelliJ IDEA Ultimate
    IntelliJPlatformType.IntellijIdeaUltimate to "2024.1.4",
    IntelliJPlatformType.IntellijIdeaUltimate to "2025.3",
    IntelliJPlatformType.IntellijIdeaUltimate to "2026.1",
)

pluginVerifierIdes.forEach { (type, version) ->
    val dependencyConfiguration = configurations.create(pluginVerifierIdeDependencyName(type, version)) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isVisible = false
    }
    configurations.create(pluginVerifierIdeName(type, version)) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isVisible = false
        attributes {
            attribute(Configurations.Attributes.extracted, true)
        }
        extendsFrom(dependencyConfiguration)
    }
    dependencies.add(dependencyConfiguration.name, intellijInstallerNotation(type, version))
}


tasks.named<PatchPluginXmlTask>("patchPluginXml") {
    untilBuild.set(provider { null })
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    forkEvery = 1
}

fun pluginVerifierIdeName(type: IntelliJPlatformType, version: String) =
    "${Configurations.INTELLIJ_PLUGIN_VERIFIER_IDES}_${type.code}_$version"

fun pluginVerifierIdeDependencyName(type: IntelliJPlatformType, version: String) =
    "${Configurations.INTELLIJ_PLUGIN_VERIFIER_IDES_DEPENDENCY}_${type.code}_$version"

fun intellijInstallerNotation(type: IntelliJPlatformType, version: String): Map<String, String> {
    val installer = requireNotNull(type.installer) {
        "No installer coordinates for ${type.code}"
    }
    val archClassifier = System.getProperty("os.arch").takeIf { it == "aarch64" }
    val osName = System.getProperty("os.name").lowercase()
    val (extension, classifier) = when {
        osName.startsWith("windows") -> "zip" to "win"
        osName.startsWith("linux") -> "tar.gz" to archClassifier
        osName.startsWith("mac") || osName.startsWith("darwin") -> "dmg" to archClassifier
        else -> error("Unsupported operating system: $osName")
    }
    return buildMap {
        put("group", installer.groupId)
        put("name", installer.artifactId)
        put("version", version)
        put("ext", extension)
        if (classifier != null) {
            put("classifier", classifier)
        }
    }
}
