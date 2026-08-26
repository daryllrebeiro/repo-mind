plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:graph"))
    implementation(project(":core:impact"))
    implementation(project(":core:query"))
    implementation(project(":core:rules"))
    implementation(project(":storage:sqlite"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":language:java"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}
