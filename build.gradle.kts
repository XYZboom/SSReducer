import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.2.21"
}

allprojects {
    repositories {
        mavenCentral()
        maven("https://www.jetbrains.com/intellij-repository/releases")
        maven("https://redirector.kotlinlang.org/maven/bootstrap/")
        maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies")
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    }
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    kotlin {
        jvmToolchain(17)
        compilerOptions {
            extraWarnings.set(true)
            jvmDefault.set(JvmDefaultMode.ENABLE)
        }
    }
}