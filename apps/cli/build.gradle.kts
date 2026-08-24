plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass = "dev.repomind.cli.MainKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:scanner"))
    implementation(project(":core:classpath"))
    implementation(project(":core:config"))
    implementation(project(":core:eval"))
    implementation(project(":core:graph"))
    implementation(project(":storage:sqlite"))
    implementation(project(":language:java"))
    implementation(libs.picocli)
    implementation(libs.kotlinx.serialization.json)
}
