plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:classpath"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.javaparser.core)
    api(libs.javaparser.symbol.solver.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}
