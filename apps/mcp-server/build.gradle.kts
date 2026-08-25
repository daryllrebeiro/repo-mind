plugins {
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.repomind.mcp.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:query"))
    implementation(project(":core:impact"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":language:java"))
    testImplementation(project(":storage:sqlite"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}
