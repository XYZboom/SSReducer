plugins {
    kotlin("jvm")
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

val aaIntellijVersion: String by project

dependencies {
    compileOnly("com.jetbrains.intellij.platform:project-model:$aaIntellijVersion")
    compileOnly("com.jetbrains.intellij.platform:project-model-impl:$aaIntellijVersion")
    api("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.13")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}