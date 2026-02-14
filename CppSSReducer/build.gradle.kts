import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory

plugins {
    kotlin("jvm")
    application
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("org.apache.commons:commons-csv:1.14.1")

    intellijPlatform {
        clion("2025.2.6")
        bundledPlugins(
            "com.intellij.cidr.lang",
            "com.intellij.nativeDebug",
            "com.intellij.clion"
        )
    }

    implementation(project(":common-util"))
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations {
    runtimeClasspath {
        extendsFrom(configurations["intellijPlatformClasspath"])
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    if (System.getProperty("java.awt.headless").toBoolean()) {
        systemProperty("java.awt.headless", "true")
    }
    if (System.getProperty("ssreducer.debug").toBoolean()) {
        jvmArgs = listOf(
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
        )
    }
    if (System.getProperty("runReduce").toBoolean()) {
        val configFile = File(layout.buildDirectory.asFile.get(), "cppSSReducerInspectionConfig.xml")
        configFile.writeText("""
            <component name="InspectionProjectProfileManager">
                <profile version="1.0">
                    <option name="myName" value="reduce only"/>
                    <inspection_tool class="CppSSReducer" enabled="true" level="WARNING" enabled_by_default="true"/>
                </profile>
            </component>
        """.trimIndent())
        val temp = createTempDirectory("CppSSReducer")
        val sourceRoot = System.getProperty("sourceRoot") ?: throw NullPointerException("sourceRoot is not set")
        argumentProviders += CommandLineArgumentProvider {
            listOf("inspect", sourceRoot, configFile.absolutePath, temp.absolutePathString())
        }
        val extraArg = System.getProperty("CppSSReducerExtraArgs")
        if (extraArg != null) {
            systemProperty("CppSSReducerExtraArgs", extraArg)
            systemProperty("idea.max.intellisense.filesize", "9999999")
            systemProperty("idea.max.content.load.filesize", "9999999")
            maxHeapSize = "32g"
            minHeapSize = "32g"
        }
    }
}

application {
    mainClass = "io.github.xyzboom.ssreducer.cpp.CppSSReducer"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}