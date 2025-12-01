import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm") version "2.2.21"
    application
    id("org.jetbrains.intellij.platform") version "2.3.0"
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://redirector.kotlinlang.org/maven/bootstrap/")
    maven("https://redirector.kotlinlang.org/maven/kotlin-ide-plugin-dependencies")
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    intellijPlatform {
        defaultRepositories()
    }
}

val aaKotlinBaseVersion: String by project
val aaIntellijVersion: String by project
val aaGuavaVersion: String by project
val aaAsmVersion: String by project
val aaFastutilVersion: String by project
val aaStax2Version: String by project
val aaAaltoXmlVersion: String by project
val aaStreamexVersion: String by project
val aaCoroutinesVersion: String by project

dependencies {
    listOf(
        "com.jetbrains.intellij.platform:util-rt",
        "com.jetbrains.intellij.platform:util-class-loader",
        "com.jetbrains.intellij.platform:util-text-matching",
        "com.jetbrains.intellij.platform:util",
        "com.jetbrains.intellij.platform:util-base",
        "com.jetbrains.intellij.platform:util-xml-dom",
        "com.jetbrains.intellij.platform:core",
        "com.jetbrains.intellij.platform:core-impl",
        "com.jetbrains.intellij.platform:extensions",
        "com.jetbrains.intellij.platform:diagnostic",
        "com.jetbrains.intellij.platform:diagnostic-telemetry",
        "com.jetbrains.intellij.java:java-frontback-psi",
        "com.jetbrains.intellij.java:java-frontback-psi-impl",
        "com.jetbrains.intellij.java:java-psi",
        "com.jetbrains.intellij.java:java-psi-impl",
        // more dependency to edit PSI tree
        "com.jetbrains.intellij.java:java-impl",
        "com.jetbrains.intellij.platform:code-style",
        "com.jetbrains.intellij.platform:code-style-impl",
        "com.jetbrains.intellij.platform:project-model",
        "com.jetbrains.intellij.platform:lang",
        "com.jetbrains.intellij.platform:lang-impl",
        "com.jetbrains.intellij.platform:ide-core",
        "com.jetbrains.intellij.platform:ide-core-impl",
        "com.jetbrains.intellij.java:java-frontback-impl",
        "com.jetbrains.intellij.platform:project-model-impl",
        "com.jetbrains.intellij.platform:configuration-store-impl",
        "com.jetbrains.intellij.jsp:jsp",
        "com.jetbrains.intellij.jsp:jsp-base",
        "com.jetbrains.intellij.jsp:jsp-spi",
        "com.jetbrains.intellij.xml:xml",
        "com.jetbrains.intellij.xml:xml-psi",
        "com.jetbrains.intellij.platform:analysis",
        "com.jetbrains.intellij.platform:analysis-impl",
        "com.jetbrains.intellij.java:java-analysis",
        "com.jetbrains.intellij.java:java-analysis-impl",
        "com.jetbrains.intellij.platform:object-serializer",
        // cpp
//        "com.jetbrains.intellij.cidr:cidr-core",
//        "com.jetbrains.intellij.cidr:cidr-psi-base",
//        "com.jetbrains.intellij.cidr:cidr-lang-base",
//        "com.jetbrains.intellij.cidr:cidr-project-model",
//        "com.jetbrains.intellij.cidr:cidr-util",
    ).forEach {
        implementation("$it:$aaIntellijVersion") { isTransitive = false }
    }
    intellijPlatform {
        clion("2024.2.5")
        bundledPlugins(
            "com.intellij.cidr.lang",
            "com.intellij.cidr.base",
            "com.intellij.nativeDebug",
        )
        bundledModules(
            "com.intellij.modules.cidr.lang",
            "com.intellij.cidr.base",
            "com.intellij.modules.cidr.debugger"
        )
    }
//    implementation("cpp:CLion:2024.2.5")
//    listOf(
//        "bundledPlugin:com.intellij.clion"
//    ).forEach {
//        implementation("$it:CL-242.26775.1") { isTransitive = false }
//    }
    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-standalone-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fe10-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-ir-for-ide",
    ).forEach {
        implementation("$it:$aaKotlinBaseVersion") { isTransitive = false }
    }
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable-jvm:0.3.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly(kotlin("stdlib", aaKotlinBaseVersion))

    implementation("com.google.guava:guava:$aaGuavaVersion")
    implementation("one.util:streamex:$aaStreamexVersion")
    implementation("org.jetbrains.intellij.deps:asm-all:$aaAsmVersion")
    implementation("org.codehaus.woodstox:stax2-api:$aaStax2Version") { isTransitive = false }
    implementation("com.fasterxml:aalto-xml:$aaAaltoXmlVersion") { isTransitive = false }
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")
    implementation("org.jetbrains.intellij.deps.jna:jna:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps.jna:jna-platform:5.9.0.26") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:trove4j:1.0.20200330") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:log4j:1.2.17.2") { isTransitive = false }
    implementation("org.jetbrains.intellij.deps:jdom:2.0.6") { isTransitive = false }
    implementation("io.javaslang:javaslang:2.0.6")
    implementation("javax.inject:javax.inject:1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation("org.lz4:lz4-java:1.7.1") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:$aaCoroutinesVersion") { isTransitive = false }
    implementation(
        "org.jetbrains.intellij.deps.fastutil:intellij-deps-fastutil:$aaFastutilVersion"
    ) {
        isTransitive = false
    }
    implementation("org.jetbrains:annotations:24.1.0")
    implementation("io.opentelemetry:opentelemetry-api:1.34.1") { isTransitive = false }

    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")

    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains:annotations:26.0.2")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

configurations {
    runtimeClasspath {
        extendsFrom(configurations["intellijPlatformClasspath"])
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        extraWarnings.set(true)
        jvmDefault.set(JvmDefaultMode.ENABLE)
    }
}

application {
    mainClass = "io.github.xyzboom.ssreducer.SSReducer"
}
