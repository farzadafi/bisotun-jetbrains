import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask

plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "ir.farzadafi"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.4")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        pluginVerifier()
    }
}

intellijPlatform {
    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.4")
            recommended()
        }
    }
}

tasks.named<PatchPluginXmlTask>("patchPluginXml") {
    untilBuild.set(provider { null })
}

kotlin {
    jvmToolchain(17)
}
