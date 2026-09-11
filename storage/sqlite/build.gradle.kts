plugins {
    alias(libs.plugins.kotlin.serialization)
    `java-library`
}

dependencies {
    api(project(":core:model"))
    api(project(":core:graph"))
    implementation(project(":core:config"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}
