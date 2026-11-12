import org.jetbrains.intellij.platform.gradle.tasks.PatchPluginXmlTask

plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "ir.farzadafi"
version = "1.0.7"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))

    intellijPlatform {
        intellijIdeaCommunity("2024.1.4")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
        pluginVerifier()
    }
}

// اصلاحیه اصلی اینجاست: استفاده از نوع رشته‌ای که در این نسخه کاملاً پشتیبانی می‌شود
intellijPlatform {
    pluginVerification {
        ides {
            ide("IC-2024.1.4")
            ide("IC-2024.2")
            ide("IC-2024.3")
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
}
