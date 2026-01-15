plugins {
    kotlin("jvm")
    application
}

group = "io.github.xyzboom"
version = "1.0-SNAPSHOT"

dependencies {
    api(project(":api"))
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    runtimeOnly(project(":KotlinJavaSSReducer"))
    runtimeOnly(project(":JVMBytecodeSSReducer"))

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass = "io.github.xyzboom.ssreducer.SSReducer"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}